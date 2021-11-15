package naxriscv.units

import naxriscv.{Frontend, Global, ROB}
import naxriscv.interfaces._
import naxriscv.utilities.Plugin
import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.Connection.{DIRECT, M2S}
import spinal.lib.pipeline.{Pipeline, Stage, Stageable}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object ExecutionUnitKeys{
  val ROB_ID = Stageable(ROB.ID_TYPE)
}

class ExecutionUnitBase(euId : String,
                        robIdStage : Int = 1,
                        contextStage : Int = 1,
                        rfReadStage : Int = 1,
                        executeStage : Int = 2) extends Plugin with ExecuteUnitService with WakeService with LockedImpl{

  override def uniqueIds = List(euId)

  override def hasFixedLatency = ???

  override def getFixedLatency = ???

  override def pushPort() = pipeline.push.port

  override def euName() = euId

  override def wakeRobs = Nil//Seq(logic.wakePort)

  val idToexecuteStages = mutable.LinkedHashMap[Int, Stage]()

  def apply(rf : RegfileSpec, access : RfAccess) = getStageable(rf -> access)
  def apply(r : RfResource) = getStageable(r)
  def getStageable(r : RfResource) : Stageable[Bits] = pipeline.rfReadData(r)
  def getExecute(id : Int) : Stage = idToexecuteStages.getOrElseUpdate(id, new Stage().setCompositeName(pipeline, s"execute_$id"))

  case class WriteBackKey(rf : RegfileSpec, access : RfAccess, stage : Stage)
  val writeBacksSpec = mutable.LinkedHashMap[WriteBackKey, ArrayBuffer[Flow[Bits]]]()
  def newWriteback(rf : RegfileSpec, access : RfAccess, stage : Stage) : Flow[Bits] = {
    val ret = Flow(Bits(rf.width bits))
    writeBacksSpec.getOrElseUpdate(WriteBackKey(rf, access, stage), ArrayBuffer[Flow[Bits]]()) += ret
    ret
  }

  val microOps = ArrayBuffer[MicroOp]()
  val stagesCompletions = mutable.LinkedHashMap[Int, ArrayBuffer[MicroOp]]()

  override def addMicroOp(microOp: MicroOp) = {
    getService[DecoderService].addEuOp(this, microOp)
    microOps += microOp
  }
  def addMicroOp(microOp: MicroOp, completionStage : Int) = {
    getService[DecoderService].addEuOp(this, microOp)
    microOps += microOp
    stagesCompletions.getOrElseUpdate(completionStage, ArrayBuffer[MicroOp]()) += microOp
  }

  val setup = create early new Area{
    getService[DecoderService].retain()
    getServicesOf[RegfileService].foreach(_.retain())
    getService[RobService].retain()
    val completion = getService[RobService].robCompletion()
  }

  val pipeline = create late new Pipeline{
    // Define stages
    val fetch = List.fill(executeStage + 1)(newStage())
    for((m,s) <- (fetch.dropRight(1), fetch.drop(1)).zipped){
      connect(m, s)(M2S())
    }

    lock.await()
    getService[DecoderService].release()

    //Create and connect execute stages
    val executeStageCount = idToexecuteStages.map(_._1).max
    val executeStages = ArrayBuffer[Stage]()
    for(i <- 0 until executeStageCount) {
      val s = getExecute(i)
      this.addStage(s)
      if(i == 0){
        connect(fetch.last, s)(DIRECT())
      } else {
        connect(fetch.last, s)(M2S())
      }
      executeStages += s
    }

    import ExecutionUnitKeys._
    val withReady = false

    val rob = getService[RobService]
    val decoder = getService[DecoderService]
    val flush = getService[CommitService].reschedulingPort().valid
    val euGroup = decoder.euGroups.find(_.eus.contains(ExecutionUnitBase.this)).get
    val sf = euGroup.eus.size
    val so = euGroup.eus.indexOf(ExecutionUnitBase.this)

    executeStages.last.flushIt(flush, root = false)

    //Figure out which register file are in use
    val ressources = mutable.LinkedHashSet[Resource]()
    for(e <- microOps; r <- e.resources) ressources += r

    //Allocate all the resources
    val rfReadPorts = mutable.LinkedHashMap[RfResource, RegFileRead]()
    val rfReads = mutable.LinkedHashSet[RfRead]()
    val rfReadData = mutable.LinkedHashMap[RfResource, Stageable[Bits]]()
    var implementRd = false
    ressources.foreach {
      case r : RfResource if r.access.isInstanceOf[RfRead] => {
        val port = getService[RegfileService](r.rf).newRead(withReady)
        val name = s"{r.rf.getName()}_${r.access.getName()}"
        port.setCompositeName(this, s"rfReads_${name}")
        rfReadPorts(r) = port
        rfReadData(r) = Stageable(Bits(r.rf.width bits)).setName(s"${euId}_${name}")
        rfReads += r.access.asInstanceOf[RfRead]
      }
      case r : RfResource if r.access.isInstanceOf[RfWrite] => implementRd = true
    }

    // Implement the fetch pipeline
    val push = new Area{
      val stage = fetch(0)
      val port = Stream(ExecutionUnitPush())
      stage.valid := port.valid
      stage(ROB_ID) := port.robId
    }

    val robId = new Area{
      val stage = fetch(robIdStage)
    }

    val context = new Area{
      val stage = fetch(contextStage)
      import stage._

      def read[T <: Data](key : Stageable[T]) = rob.readAsyncSingle(key, ROB_ID, sf, so)
      def readAndInsert[T <: Data](key : Stageable[T]) = stage(key) := read(key)

      readAndInsert(Frontend.INSTRUCTION_DECOMPRESSED)
      for(e <- rfReads){
        readAndInsert(decoder.PHYS_RS(e))
        readAndInsert(decoder.READ_RS(e))
      }
      if(implementRd){
        readAndInsert(decoder.PHYS_RD)
        readAndInsert(decoder.WRITE_RD)
      }
      if(ressources.contains(PC_READ)){
        readAndInsert(Global.PC)
      }
    }

    val readRf = new Area{
      val stage = fetch(rfReadStage)
      import stage._

      for((rf, port) <- rfReadPorts){
        val id = rf.access.asInstanceOf[RfRead]
        port.valid := decoder.READ_RS(id)
        port.address := decoder.ARCH_RS(id)
        rfReadData(rf) := port.data
      }
    }

    val writeBack = for((spec, writes) <- writeBacksSpec) yield new Area{
      val rfService = getService[RegfileService](spec.rf)
      val port = rfService.newWrite(withReady)
      port.valid := spec.stage.isFireing && spec.stage(decoder.WRITE_RD)
      port.robId := spec.stage(ROB_ID)
      port.address := spec.stage(decoder.PHYS_RD)
      port.data := MuxOH.or(writes.map(_.valid), writes.map(_.payload))
    }

    val completion = for((stageId, microOp) <- stagesCompletions) yield new Area{
      val port = rob.robCompletion()
      port.valid := ???
      port.id := executeStages(stageId)(ROB_ID)
    }


    getServicesOf[RegfileService].foreach(_.release())
    rob.release()
    this.build()
  }

}