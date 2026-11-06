// See LICENSE for license details.

package goldengate.tests

import org.scalatest.flatspec.AnyFlatSpec

import org.chipsalliance.cde.config.{Config, Parameters}

import junctions.NastiKey
import firesim.lib.nasti.NastiParameters
import midas.models._

/** Elaborates the FASED HBM2 timing model (and the DDR3 FR-FCFS model as a
  * control) to catch hardware construction errors: width mismatches, invalid
  * connections, unassigned wires, etc.
  */
class HBMModelSpec extends AnyFlatSpec with ElaborationUtils {

  implicit val p: Parameters = new Config((_, _, _) => { case NastiKey =>
    NastiParameters(dataBits = 64, addrBits = 32, idBits = 4)
  })

  def baseParams = BaseParams(maxReads = 16, maxWrites = 16)

  def hbmConfig = HBMModelConfig(
    hbmKey                = HBMOrganizationParams(
      maxPseudoChannels = 2,
      maxBankGroups     = 4,
      banksPerGroup     = 4,
      channelSize       = BigInt(1) << 34,
    ),
    schedulerWindowSize   = 8,
    transactionQueueDepth = 8,
    params                = baseParams,
  )

  def frfcfsConfig = FirstReadyFCFSConfig(
    dramKey               = DramOrganizationParams(maxBanks = 8, maxRanks = 4, dramSize = BigInt(1) << 34),
    schedulerWindowSize   = 8,
    transactionQueueDepth = 8,
    params                = baseParams,
  )

  behavior.of("FirstReadyFCFSModel (control)")

  it should "elaborate and lower to Verilog" in {
    elaborateAndLower(new FirstReadyFCFSModel(frfcfsConfig))
  }

  behavior.of("HBMModel")

  it should "elaborate and lower to Verilog" in {
    elaborateAndLower(new HBMModel(hbmConfig))
  }

  it should "elaborate and lower to Verilog with four channels" in {
    elaborateAndLower(
      new HBMModel(hbmConfig.copy(hbmKey = hbmConfig.hbmKey.copy(maxChannels = 4), requestTrace = true))
    )
  }

  def hbm4Key = new firesim.configs.HBM4FRFCFS16HiQuadChannel().apply(firesim.configs.HBMOrganizationKey)

  it should "take the HBM4 16-High organization from HBM4FRFCFS16HiQuadChannel" in {
    assert(hbm4Key.maxBanks == 64 && hbm4Key.maxBankGroups == 8 && hbm4Key.maxChannels == 4)
    assert(hbm4Key.maxPseudoChannels == 2)
  }

  it should "elaborate and lower to Verilog with the HBM4 16-High organization" in {
    elaborateAndLower(new HBMModel(hbmConfig.copy(hbmKey = hbm4Key, requestTrace = true)))
  }

  def hbm2x8HiKey = new firesim.configs.HBM2FRFCFS8HiQuadChannel().apply(firesim.configs.HBMOrganizationKey)

  it should "take the HBM2 8-High organization from HBM2FRFCFS8HiQuadChannel" in {
    assert(hbm2x8HiKey.maxBanks == 32 && hbm2x8HiKey.maxBankGroups == 8 && hbm2x8HiKey.banksPerGroup == 4)
    assert(hbm2x8HiKey.maxChannels == 4 && hbm2x8HiKey.maxPseudoChannels == 2 && hbm2x8HiKey.sidBits == 1)
    assert((0 until 8).map(hbm2x8HiKey.sidOfGroup) == Seq(0, 0, 0, 0, 1, 1, 1, 1))
  }

  it should "elaborate and lower to Verilog with the HBM2 8-High organization" in {
    elaborateAndLower(new HBMModel(hbmConfig.copy(hbmKey = hbm2x8HiKey, requestTrace = true)))
  }

  it should "add the tCCDR register only to organizations with stack-ID bits" in {
    def names(cfg: HBMModelConfig) = {
      val regs = new HBMMMRegIO(cfg)
      regs.registers.map { case (elem, _) => regs.getName(elem) }.toSet
    }
    val quad = hbmConfig.copy(hbmKey = hbmConfig.hbmKey.copy(maxChannels = 4))
    assert(!names(hbmConfig).contains("tCCDR") && !names(quad).contains("tCCDR"))
    assert(!names(hbmConfig.copy(hbmKey = hbm4Key)).contains("tCCDR"))
    assert(names(hbmConfig.copy(hbmKey = hbm2x8HiKey)) == names(quad) + "tCCDR")
  }

  it should "provide enough default backend entries to fill one 8-byte AXI port" in {
    assert(hbmConfig.backendKey.readDepth == 8)
    assert(hbmConfig.backendKey.writeDepth == 8)
    // A 32-byte CAS occupies four cycles at the AXI width. With tCAS=17
    // and backendLatency=2, at least five in-flight read completions are
    // needed before the backend stops being the throughput limiter.
    assert(hbmConfig.backendKey.readDepth >= math.ceil((17.0 + 2.0) / 4.0).toInt)
    elaborateAndLower(new HBMModel(hbmConfig))
  }

  it should "default to round-robin REFSB without postponement or bank hashing" in {
    val regs     = new HBMMMRegIO(hbmConfig)
    val defaults = regs.registers.map { case (elem, setting) => regs.getName(elem) -> setting }.toMap
    assert(defaults("refsbIdleFirst").default == 0)
    assert(defaults("refsbMaxPostponed").default == 0)
    assert(defaults("refsbIdleAge").default == 0)
    assert(defaults("refsbYield").default == 0)
    assert(defaults("refsbLRU").default == 0)
    assert(defaults("refsbAgeScale").default == 0)
    assert(defaults("bankXorHash").default == 0)
  }

  it should "cap REFSB postponement within JESD270-4A 6.3.2.5" in {
    for (cfg <- Seq(hbmConfig, hbmConfig.copy(hbmKey = hbm4Key), hbmConfig.copy(hbmKey = hbm2x8HiKey))) {
      val regs     = new HBMMMRegIO(cfg)
      val defaults = regs.registers.map { case (elem, setting) => regs.getName(elem) -> setting }.toMap
      val banks    = cfg.hbmKey.maxBanks
      val maxOwed  = defaults("refsbMaxPostponed").max.get.toInt
      assert(maxOwed == 6 * banks)
      // Worst case between two refreshes of one bank: it opens one set and
      // closes the next, and up to maxOwed + 1 refreshes are owed meanwhile.
      assert((2 * banks - 1 + maxOwed + 1) <= 9 * banks)
      // A catch-up from maxOwed + 1 owed, plus one accrual, refreshes a bank
      // at most this often within tREFI.
      assert(math.ceil((maxOwed + 1 + banks).toDouble / banks).toInt <= 9)
      // At most eight all-bank refreshes' worth may be postponed.
      assert(maxOwed + 1 <= 8 * banks)
      assert(defaults("refsbIdleAge").max.contains(4095))
    }
  }

  it should "have complete HBM2 timing tables" in {
    val timings = new HBMProgrammableTimings()
    val fields  = timings.registers.map { case (elem, _) => timings.getName(elem) }.toSet
    for ((table, name) <- HBMTimingTables.grades) {
      assert(
        fields.subsetOf(table.keySet) && table.keySet.subsetOf(fields),
        s"Timing table '${name}' keys must exactly match HBMProgrammableTimings fields",
      )
    }
  }
}
