package naxriscv

import naxriscv.backend.{CommitPlugin, RegFilePlugin, RobPlugin}
import spinal.core._
import naxriscv.frontend._
import naxriscv.interfaces.{ExecutionUnitPush, Riscv}
import naxriscv.units.{ExecuteUnit, IntAluPlugin}
import naxriscv.utilities._

import scala.collection.mutable.ArrayBuffer

object Config{
  def properties() = {
//    Global.PHYSICAL_WIDTH.set(32)
//    Frontend.RVC.set(true)
//    Frontend.FETCH_DATA_WIDTH.set(64)
//    Frontend.INSTRUCTION_WIDTH.set(32)
//    Frontend.DECODE_COUNT.set(2)
//    Global.COMMIT_COUNT.set(2)
//    ROB.SIZE.set(64)

    Global.PHYSICAL_WIDTH.set(32)
    Frontend.RVC.set(true)
    Frontend.FETCH_DATA_WIDTH.set(32)
    Frontend.INSTRUCTION_WIDTH.set(32)
    Frontend.DECODE_COUNT.set(1)
    Global.COMMIT_COUNT.set(1)
    ROB.SIZE.set(64)
  }
  def plugins(): Seq[Plugin] ={
    val plugins = ArrayBuffer[Plugin]()
    plugins += new DocPlugin()
    plugins += new FrontendPlugin()
    plugins += new DirectAddressTranslationPlugin()
    plugins += new PcPlugin()
    plugins += new FetchCachePlugin(
      cacheSize = 4096,
      wayCount = 1,
      injectionAt = 2,
      memDataWidth = 32
    )
    plugins += new AlignerPlugin()
    plugins += new DecompressorPlugin()
    plugins += new DecoderPlugin()
    plugins += new RfTranslationPlugin()
    plugins += new RfDependencyPlugin()
    plugins += new RfAllocationPlugin(Riscv.integer.regfile)
    plugins += new DisspatchPlugin(
      slotCount = 32
    )
    plugins += new ExecuteUnit("ALU0")
    plugins += new IntAluPlugin("ALU0")
    plugins += new RobPlugin()
    plugins += new CommitPlugin()
    plugins += new RegFilePlugin(
      spec = Riscv.integer.regfile,
      physicalDepth = 64
    )
    plugins += new PlayPlugin()
    plugins
  }
}
object Gen extends App{
  val report = SpinalVerilog(new Component {
    setDefinitionName("NaxRiscv")
    Config.properties()
    val framework = new Framework(Config.plugins())
  })
  val doc = report.toplevel.framework.getService[DocPlugin]
  doc.genC()
}

//object GenSim extends App{
//  import spinal.core.sim._
//  SimConfig.withFstWave.compile(new Component {
//    Config.properties()
//    val frontend = new Framework(Config.plugins())
//  }).doSim(seed = 42){}
//}


//TODO fix bellow list
/*
- aligner flush connector from fetches.last stage
 */