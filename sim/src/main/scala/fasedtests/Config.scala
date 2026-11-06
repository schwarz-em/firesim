//See LICENSE for license details.
package firesim.fasedtests

import chisel3.util.isPow2

import org.chipsalliance.cde.config.{Config, Field}
import freechips.rocketchip.subsystem.{
  ExtMem,
  MasterPortParams,
  MemoryPortParams,
  WithNMemoryChannels,
  WithoutTLMonitors,
}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import firesim.configs._
import firesim.lib.nasti.NastiParameters

case object AXI4SlavePort        extends Field[AXI4SlavePortParameters]
case object MaxTransferSize      extends Field[Int](64)
case object BeatBytes            extends Field[Int](8)
case object IDBits               extends Field[Int](4)
case object AddrBits             extends Field[Int](22)
case object NumTransactions      extends Field[Int](10000)
case object MaxFlight            extends Field[Int](128)
case object NumMemoryChannels    extends Field[Int](1)
case object FuzzerAddressMaskKey extends Field[BigInt]

class WithSlavePortParams
    extends Config((site, _, _) => { case AXI4SlavePort =>
      AXI4SlavePortParameters(
        slaves    = Seq(
          AXI4SlaveParameters(
            address       = Seq(AddressSet(BigInt(0), (BigInt(1) << site(AddrBits)) - 1)),
            regionType    = RegionType.UNCACHED,
            executable    = true,
            supportsWrite = TransferSizes(1, site(MaxTransferSize)),
            supportsRead  = TransferSizes(1, site(MaxTransferSize)),
            interleavedId = Some(0),
          )
        ),
        beatBytes = site(BeatBytes),
      )
    })

class WithDefaultMemPort
    extends Config((site, _, _) => { case ExtMem =>
      Some(
        MemoryPortParams(
          MasterPortParams(
            base      = BigInt(0),
            size      = BigInt(1) << site(AddrBits),
            beatBytes = site(BeatBytes),
            idBits    = site(IDBits),
          ),
          1,
        )
      )
    })

class WithDefaultFuzzer
    extends Config((site, _, _) => {
      case FuzzerAddressMaskKey => (BigInt(1) << site(AddrBits)) - 1
      case FuzzerParametersKey  =>
        Seq(FuzzerParameters(site(NumTransactions), site(MaxFlight), Some(AddressSet(0, site(FuzzerAddressMaskKey)))))
    })

// Configures the total number of transactions generated
class WithNTransactions(num: Int)
    extends Config((_, _, _) => { case NumTransactions =>
      num
    })

class NT10e3 extends WithNTransactions(1000)
class NT10e5 extends WithNTransactions(100000)
class NT10e6 extends WithNTransactions(1000000)
class NT10e7 extends WithNTransactions(10000000)

// Provides an address mask to fuzzer to constrain generated addresses
class WithFuzzerMask(mask: BigInt)
    extends Config((_, _, _) => { case FuzzerAddressMaskKey =>
      mask
    })
class FuzzMask3FFF extends WithFuzzerMask(0x3fff)

// With the default HBM address layout ([9:0] line | [13:10] bank | [14] PC |
// [21:15] row), constrain the fuzzer to PC 0, bank 0 while letting the row
// bits vary: a directed row-conflict / bank-timing (tRC/tRAS/tRP) stressor.
class FuzzMaskSingleBank extends WithFuzzerMask(0x3f83ff)

// Directed multi-channel boundary stressor: with the quad-channel contiguous
// partition of the 22-bit fuzzer space (1 MiB per channel, channel index in
// address bits [21:20]), fuzz exactly the first and the last 64B line of
// every channel region: {0x000000, 0x100000, 0x200000, 0x300000} and
// {0x0FFFC0, 0x1FFFC0, 0x2FFFC0, 0x3FFFC0}. Every request lands on a
// channel-partition edge -- including the lowest and highest modeled
// addresses -- so the request trace proves the boundary routing exactly.
class FuzzChannelBoundaries
    extends Config((site, _, _) => { case FuzzerParametersKey =>
      Seq(
        FuzzerParameters(site(NumTransactions) / 2, site(MaxFlight), Some(AddressSet(0x000000, 0x300000))),
        FuzzerParameters(site(NumTransactions) / 2, site(MaxFlight), Some(AddressSet(0x0fffc0, 0x300000))),
      )
    })

// Generates N fuzzers mastering non-overlapping chunks of the target memory space
class WithNFuzzers(numFuzzers: Int)
    extends Config((site, _, _) => { case FuzzerParametersKey =>
      Seq.tabulate(numFuzzers) { i =>
        require(isPow2(numFuzzers))
        val regionSize = (BigInt(1) << site(AddrBits)) / numFuzzers
        FuzzerParameters(
          site(NumTransactions) / numFuzzers,
          site(MaxFlight),
          Some(AddressSet(regionSize * i, (regionSize - 1) & site(FuzzerAddressMaskKey))),
        )
      }
    })
class QuadFuzzer extends WithNFuzzers(4)

// Configures the size of the target memory system
class WithNAddressBits(num: Int)
    extends Config((_, _, _) => { case AddrBits =>
      num
    })
class AddrBits16 extends WithNAddressBits(16)
class AddrBits22 extends WithNAddressBits(22)

// Number of target memory channels -> number of FASED instances
class QuadChannel extends WithNMemoryChannels(4)

/** Complete target configurations
  */

class DefaultConfig
    extends Config(
      new WithoutTLMonitors ++
        new WithDefaultFuzzer ++
        new WithDefaultMemPort ++
        new Config((site, _, _) => { case junctions.NastiKey =>
          NastiParameters(site(BeatBytes) * 8, site(AddrBits), site(IDBits))
        })
    )

class FCFSConfig
    extends Config(
      new FCFS16GBQuadRank ++
        new DefaultConfig
    )

class FRFCFSConfig
    extends Config(
      new FRFCFS16GBQuadRank ++
        new DefaultConfig
    )

class LLCDRAMConfig
    extends Config(
      new FRFCFS16GBQuadRankLLC4MB ++
        new DefaultConfig
    )

class BankedLLCDRAMConfig
    extends Config(
      new WithLLCModel(4096, 8, banks = 2) ++
        new FRFCFS16GBQuadRank ++
        new DefaultConfig
    )

/** Host memory system fragments
  */

class WithNHostIdBits(num: Int)
    extends Config((_, _, up) => { case midas.core.HostMemChannelKey =>
      up(midas.core.HostMemChannelKey).copy(idBits = num)
    })

class ConstrainHostIds(idBits: Int)
    extends Config((_, _, _) => { case midas.core.HostMemIdSpaceKey =>
      Some(midas.core.AXI4IdSpaceConstraint(idBits))
    })

/** Complete platform / compiler configurations
  */

class DefaultF1Config
    extends Config(
      new WithDefaultMemModel ++
        new midas.F1Config
    )

class DefaultF2Config
    extends Config(
      new WithDefaultMemModel ++
        new midas.F2Config
    )

// AXI4Fuzzer against the HBM2 pseudo-channel timing model (for stress
// testing the model's legality assertions and extracting command traces)
class HBMF2Config
    extends Config(
      new HBM2FRFCFS16GBDualPC ++
        new midas.F2Config
    )

// Explicit completion depths preserve the legacy four-entry baseline and the
// eight-entry corrected variant for reproducible model A/B tests.
class HBMF2Backend4Config
    extends Config(
      new WithHBMBackendEntries(4) ++
        new HBMF2Config
    )

class HBMF2Backend8Config
    extends Config(
      new WithHBMBackendEntries(8) ++
        new HBMF2Config
    )

// Matched single-AXI-port DDR control for FixedReadProbe. The target's
// DefaultConfig supplies the same 64-bit AXI widths as the HBM variants.
class DDRF2Config
    extends Config(
      new FRFCFS8GBQuadRank ++
        new midas.F2Config
    )

// As HBMF2Config, but additionally emits one "HBMREQ,..." CSV line per
// memory transaction accepted by the HBM scheduler (single-channel request
// trace for controller-vs-model validation).
class HBMF2ReqTraceConfig
    extends Config(
      new WithHBMRequestTrace ++
        new HBMF2Config
    )

// AXI4Fuzzer against the quad-channel HBM2 model: four independent
// per-channel schedulers (bank/BG/PC timing state, refresh, command buses)
// behind one AXI4 port. Channel decode is runtime-programmable via the
// mm_chAddr_* registers.
class HBMF2QuadChConfig
    extends Config(
      new WithHBMQuadChannel ++
        new HBMF2Config
    )

// Quad-channel HBM with the per-request CSV trace enabled (used to validate
// request-to-channel routing against the programmed address partition).
class HBMF2QuadChReqTraceConfig
    extends Config(
      new WithHBMRequestTrace ++
        new HBMF2QuadChConfig
    )

class SmallQuadChannelHostConfig
    extends Config(new Config((site, _, _) => {
      case midas.core.HostMemNumChannels => 4
      case midas.core.HostMemChannelKey  =>
        midas.core.HostMemChannelParams(
          size      = (BigInt(1) << site(AddrBits)) / site(midas.core.HostMemNumChannels),
          beatBytes = 8,
          idBits    = 6,
        )
    }) ++ new DefaultF1Config)

class ConstrainedIdHostConfig
    extends Config(
      new ConstrainHostIds(2) ++
        new WithNHostIdBits(2) ++
        new DefaultF1Config
    )
