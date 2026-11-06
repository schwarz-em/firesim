//See LICENSE for license details.
package firesim.configs

import org.chipsalliance.cde.config.{Config, Field}

import midas.models._

case object MemModelKey         extends Field[BaseConfig]
case object BaseParamsKey       extends Field[BaseParams]
case object LlcKey              extends Field[Option[LLCParams]]
case object DramOrganizationKey extends Field[DramOrganizationParams]
case object HBMOrganizationKey  extends Field[HBMOrganizationParams]

// Instantiates an AXI4 memory model that executes (1 / clockDivision) of the frequency
// of the RTL transformed model (Rocket Chip)
class WithDefaultMemModel
    extends Config((site, _, _) => {
      case LlcKey              => None
      // Only used if a DRAM model is requested
      case DramOrganizationKey => DramOrganizationParams(maxBanks = 8, maxRanks = 4, dramSize = BigInt(1) << 34)
      // Default to a Latency-Bandwidth Pipe without and LLC model
      case BaseParamsKey       => BaseParams(maxReads = 16, maxWrites = 16, beatCounters = true, llcKey = site(LlcKey))

      case MemModelKey => new LatencyPipeConfig(site(BaseParamsKey))

      // Only used if an HBM model is requested. One HBM2 channel in
      // pseudo-channel mode: 2 PCs x 4 bank groups x 4 banks.
      case HBMOrganizationKey =>
        HBMOrganizationParams(
          maxPseudoChannels = 2,
          maxBankGroups     = 4,
          banksPerGroup     = 4,
          channelSize       = BigInt(1) << 34,
        )
    })

/** ***************************************************************************** Memory-timing model configuration
  * modifiers
  */

// Adds a LLC model with at most <maxSets> sets with <maxWays> ways.
// banks > 1 creates independent LLC banks to reduce head-of-line blocking.
// Constraint: log2(banks) + log2(mshrs.max) <= AXI ID width (typically 4 bits).
class WithLLCModel(maxSets: Int, maxWays: Int, banks: Int = 1)
    extends Config((_, _, _) => { case LlcKey =>
      Some(
        LLCParams().copy(
          ways  = WRange(1, maxWays),
          sets  = WRange(1, maxSets),
          banks = banks,
        )
      )
    })

// Changes the default DRAM memory organization.
class WithDramOrganization(maxRanks: Int, maxBanks: Int, dramSize: BigInt)
    extends Config((_, _, up) => { case DramOrganizationKey =>
      up(DramOrganizationKey).copy(
        maxBanks = maxBanks,
        maxRanks = maxRanks,
        dramSize = dramSize,
      )
    })

// Instantiates a DDR3 model with a FCFS memory access scheduler
class WithDDR3FIFOMAS(queueDepth: Int)
    extends Config((site, _, _) => { case MemModelKey =>
      new FIFOMASConfig(
        transactionQueueDepth = queueDepth,
        dramKey               = site(DramOrganizationKey),
        params                = site(BaseParamsKey),
      )
    })

// Instantiates a DDR3 model with a FR-FCFS memory access scheduler
// windowSize = Maximum number of references the MAS can schedule across
class WithDDR3FRFCFS(windowSize: Int, queueDepth: Int)
    extends Config((site, _, _) => { case MemModelKey =>
      new FirstReadyFCFSConfig(
        schedulerWindowSize   = windowSize,
        transactionQueueDepth = queueDepth,
        dramKey               = site(DramOrganizationKey),
        params                = site(BaseParamsKey),
      )
    })

// Instantiates an HBM2 (pseudo-channel mode) model with a FR-FCFS memory
// access scheduler. Timings are runtime-programmable; defaults are the
// HBM2-2400 table (see HBMTimingTables for alternates, including PARE).
class WithHBM2FRFCFS(windowSize: Int, queueDepth: Int)
    extends Config((site, _, _) => { case MemModelKey =>
      new HBMModelConfig(
        schedulerWindowSize   = windowSize,
        transactionQueueDepth = queueDepth,
        hbmKey                = site(HBMOrganizationKey),
        params                = site(BaseParamsKey),
      )
    })

// Sets the number of independent HBM channels modeled by the HBM timing
// model (per-channel schedulers and timing state inside one HBMModel
// instance; see HBMModel.scala). Layer *in front of* a config that sets
// HBMOrganizationKey (e.g. WithDefaultMemModel / HBM2FRFCFS16GBDualPC).
class WithHBMChannels(n: Int)
    extends Config((_, _, up) => { case HBMOrganizationKey =>
      up(HBMOrganizationKey).copy(maxChannels = n)
    })

// Named alias so quad-channel HBM can be requested in underscore-composed
// PLATFORM_CONFIG strings, e.g.:
//   WithHBMQuadChannel_HBM2FRFCFS16GBDualPC_BaseF2Config
class WithHBMQuadChannel extends WithHBMChannels(4)

// Sets the banks per pseudo channel of the HBM timing model. Layer *in front
// of* a config that sets HBMOrganizationKey.
class WithHBMBankOrganization(bankGroups: Int, banksPerGroup: Int)
    extends Config((_, _, up) => { case HBMOrganizationKey =>
      up(HBMOrganizationKey).copy(maxBankGroups = bankGroups, banksPerGroup = banksPerGroup)
    })

// Marks the top `sidBits` bank-group bits as stack-ID bits (tCCDR applies
// between them). Layer *in front of* a config that sets HBMOrganizationKey.
class WithHBMStackIDBits(sidBits: Int)
    extends Config((_, _, up) => { case HBMOrganizationKey =>
      up(HBMOrganizationKey).copy(sidBits = sidBits)
    })

// Enables the per-request CSV trace ("HBMREQ,..." lines on metasim stdout)
// on an already-selected HBM memory model. Layer *in front of* a config that
// sets MemModelKey to an HBMModelConfig, e.g.:
//   new WithHBMRequestTrace ++ new HBM2FRFCFS16GBDualPC ++ ...
class WithHBMRequestTrace
    extends Config((_, _, up) => { case MemModelKey =>
      up(MemModelKey) match {
        case hbm: HBMModelConfig => hbm.copy(requestTrace = true)
        case other               =>
          throw new IllegalArgumentException(
            s"WithHBMRequestTrace requires an HBMModelConfig, got: ${other.getClass.getName}"
          )
      }
    })

// Diagnostic override for the number of in-flight HBM completions. The
// legacy four-entry backend can retire at most four 32-byte reads per 19
// target cycles (tCAS=17 plus two backend cycles), below the 8-byte/cycle
// AXI port limit. The production default is now eight entries; this
// override preserves a controlled A/B against the former capacity.
class WithHBMBackendEntries(entries: Int)
    extends Config((_, _, up) => { case MemModelKey =>
      require(entries > 0, "HBM backend entries must be positive")
      up(MemModelKey) match {
        case hbm: HBMModelConfig =>
          hbm.copy(backendKey = DRAMBackendKey(entries, entries, hbm.backendKey.latencyBits))
        case other =>
          throw new IllegalArgumentException(
            s"WithHBMBackendEntries requires an HBMModelConfig, got: ${other.getClass.getName}"
          )
      }
    })

// Changes the functional model capacity limits
class WithFuncModelLimits(maxReads: Int, maxWrites: Int)
    extends Config((_, _, up) => { case BaseParamsKey =>
      up(BaseParamsKey).copy(
        maxReads  = maxReads,
        maxWrites = maxWrites,
      )
    })

/** ***************************************************************************** Complete Memory-Timing Model
  * Configurations
  */
// Latency Bandwidth Pipes
class LBP32R32W
    extends Config(
      new WithFuncModelLimits(32, 32) ++
        new WithDefaultMemModel
    )

class LBP32R32WLLC4MB
    extends Config(
      new WithLLCModel(4096, 8) ++
        new WithFuncModelLimits(32, 32) ++
        new WithDefaultMemModel
    )

// DDR3 - FCFS models.
class FCFS16GBQuadRank extends Config(new WithDDR3FIFOMAS(8) ++ new WithDefaultMemModel)
class FCFS16GBQuadRankLLC4MB
    extends Config(
      new WithLLCModel(4096, 8) ++
        new FCFS16GBQuadRank
    )

// DDR3 - First-Ready FCFS models
class FRFCFS16GBQuadRank
    extends Config(
      new WithFuncModelLimits(32, 32) ++
        new WithDDR3FRFCFS(8, 8) ++
        new WithDefaultMemModel
    )
class FRFCFS16GBQuadRankLLC4MB
    extends Config(
      new WithLLCModel(4096, 8) ++
        new FRFCFS16GBQuadRank
    )
// DDR3 FR-FCFS, 64 GB, 4 ranks x 8 banks
class FRFCFS64GBQuadRank
    extends Config(
      new WithDramOrganization(maxRanks = 4, maxBanks = 8, dramSize = BigInt(1) << 36) ++
        new FRFCFS16GBQuadRank
    )
class FRFCFS64GBQuadRankLLC4MB
    extends Config(
      new WithLLCModel(4096, 8) ++
        new FRFCFS64GBQuadRank
    )
class FRFCFS64GBQuadRankBankedLLC4MB
    extends Config(
      new WithLLCModel(2048, 8, banks = 2) ++
        new FRFCFS64GBQuadRank
    )

// DDR3 - FCFS models, 8GB
class FCFS8GBQuadRank
    extends Config(
      new WithDramOrganization(maxRanks = 4, maxBanks = 8, dramSize = BigInt(1) << 33) ++
        new WithDDR3FIFOMAS(8) ++
        new WithDefaultMemModel
    )
class FCFS8GBQuadRankLLC4MB
    extends Config(
      new WithLLCModel(4096, 8) ++
        new FCFS8GBQuadRank
    )

// DDR3 - First-Ready FCFS models, 8GB
class FRFCFS8GBQuadRank
    extends Config(
      new WithFuncModelLimits(32, 32) ++
        new WithDDR3FRFCFS(8, 8) ++
        new WithDramOrganization(maxRanks = 4, maxBanks = 8, dramSize = BigInt(1) << 33) ++
        new WithDefaultMemModel
    )
class FRFCFS8GBQuadRankLLC4MB
    extends Config(
      new WithLLCModel(4096, 8) ++
        new FRFCFS8GBQuadRank
    )

// 2 LLC banks of up to 2048 sets x 8 ways: 4 MB with 128 B blocks, the capacity of LLC4MB.
// Ways and sets are powers of two, so a 6 MB, 6-way cache cannot be modeled.
class FRFCFS16GBQuadRankBankedLLC4MB
    extends Config(
      new WithLLCModel(2048, 8, banks = 2) ++
        new FRFCFS16GBQuadRank
    )

// HBM2 - First-Ready FCFS models (one channel, dual pseudo-channel)
class HBM2FRFCFS16GBDualPC
    extends Config(
      new WithFuncModelLimits(32, 32) ++
        new WithHBM2FRFCFS(8, 8) ++
        new WithDefaultMemModel
    )
class HBM2FRFCFS16GBDualPCLLC4MB
    extends Config(
      new WithLLCModel(4096, 8) ++
        new HBM2FRFCFS16GBDualPC
    )

// As HBM2FRFCFS16GBDualPC, but modeling four independent HBM channels
// (four per-channel schedulers inside one HBMModel). The channel decode is
// runtime-programmable (mm_chAddr_* registers); the default is a contiguous
// partition with the channel index directly above one channel's capacity.
class HBM2FRFCFS16GBQuadChannel
    extends Config(
      new WithHBMQuadChannel ++
        new HBM2FRFCFS16GBDualPC
    )

// HBM4 16-High (JESD270-4A Tables 4 and 5): 64 banks per pseudo channel
// (4 stack IDs x 16 banks, 8 bank groups of 8) and a 1 KB page per pseudo
// channel. The model takes the bank group from the low bank-index bits, so its
// group g is the spec's group {SID, BA[3]} up to a renumbering of the banks.
// Four channels, as HBM2FRFCFS16GBQuadChannel; timings are runtime registers.
class HBM4FRFCFS16HiQuadChannel
    extends Config(
      new WithHBMBankOrganization(bankGroups = 8, banksPerGroup = 8) ++
        new HBM2FRFCFS16GBQuadChannel
    )

// HBM2 8-High, 8 Gb per channel (JESD235D Tables 4 and 5): 32 banks per pseudo
// channel (SID, BA[3:0]) in 8 bank groups of 4 (A-D: SID 0, E-H: SID 1) and a
// 1 KB page per pseudo channel. The model's group g has SID g[2]; reads to the
// other SID are spaced by the tCCDR register. Four channels, as
// HBM2FRFCFS16GBQuadChannel; timings are runtime registers.
class HBM2FRFCFS8HiQuadChannel
    extends Config(
      new WithHBMStackIDBits(1) ++
        new WithHBMBankOrganization(bankGroups = 8, banksPerGroup = 4) ++
        new HBM2FRFCFS16GBQuadChannel
    )
