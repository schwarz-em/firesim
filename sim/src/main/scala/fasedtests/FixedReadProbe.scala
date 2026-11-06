// See LICENSE for license details.
package firesim.fasedtests

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters

import junctions._

import firesim.lib.bridges.{CompleteConfig, FASEDBridge, PeekPokeBridge, RationalClockBridge, ResetPulseBridge, ResetPulseBridgeParameters}
import firesim.lib.nasti._

/** Fixed sequential 32-byte read stream for memory-model throughput A/B tests.
  *
  * Unlike TLFuzzer, the request addresses and read/write mix do not depend on
  * response timing. A bounded 16-request window keeps the same source pressure
  * on each model while all accepted requests are checked for four data beats,
  * an OKAY response, and one terminal beat.
  */
class FixedReadProbeDUT(
  nastiParams: NastiParameters,
  requests: Int,
  baseAddr: BigInt,
  interleaveBanks: Boolean = false,
)(implicit val p: Parameters)
    extends NastiModule(nastiParams) {
  private val countBits = log2Ceil(requests + 1)
  private val beatBits = log2Ceil(4 * requests + 1)
  require(nastiParams.dataBits == 64 && nastiParams.idBits == 4 && nastiParams.addrBits >= 19)
  require(requests >= 16 && baseAddr >= 0 && baseAddr + BigInt(requests) * 32 <= (BigInt(1) << nastiParams.addrBits))
  require(!interleaveBanks || countBits >= 10)

  val io = IO(new Bundle {
    val nasti = new NastiIO(nastiParams)
    val done  = Output(Bool())
    val error = Output(Bool())
  })

  val issued = RegInit(0.U(countBits.W))
  val completed = RegInit(0.U(countBits.W))
  val beats = RegInit(0.U(beatBits.W))
  val responseError = RegInit(false.B)
  val beatsForId = RegInit(VecInit(Seq.fill(16)(0.U(2.W))))

  // With HBM's bank bits at byte [13:10], this bit permutation preserves the
  // exact address set but presents one 32-byte line from each of 16 banks in
  // succession. The default path remains strictly sequential for A/B tests.
  val lineIndex = if (interleaveBanks) {
    Cat(issued(countBits - 1, 9), issued(3, 0), issued(8, 4))
  } else issued
  io.nasti.ar.bits := NastiWriteAddressChannel(
    nastiParams,
    id   = issued(3, 0),
    len  = 3.U,
    size = 3.U, // four 8-byte beats per 32-byte cache line
    addr = baseAddr.U(nastiXAddrBits.W) + (lineIndex << 5),
  )
  io.nasti.ar.valid := issued < requests.U && (issued - completed) < 16.U
  when(io.nasti.ar.fire) { issued := issued + 1.U }

  io.nasti.r.ready := true.B
  when(io.nasti.r.fire) {
    beats := beats + 1.U
    when(io.nasti.r.bits.last) { completed := completed + 1.U }
    when(io.nasti.r.bits.resp =/= 0.U) { responseError := true.B }
    when(io.nasti.r.bits.last =/= (beatsForId(io.nasti.r.bits.id) === 3.U)) {
      responseError := true.B
    }
    beatsForId(io.nasti.r.bits.id) := beatsForId(io.nasti.r.bits.id) + 1.U
  }

  io.nasti.aw.bits := NastiWriteAddressChannel(nastiParams, id = 0.U, len = 0.U, size = 3.U, addr = 0.U)
  io.nasti.aw.valid := false.B
  io.nasti.w.bits := NastiWriteDataChannel(nastiParams, 0.U)
  io.nasti.w.valid := false.B
  io.nasti.b.ready := true.B

  io.error := responseError || beats > (4 * requests).U || completed > requests.U
  io.done := issued === requests.U && completed === requests.U && beats === (4 * requests).U && !io.error
}

class FixedReadProbeTop(numPorts: Int, requestsPerPort: Int, interleaveBanks: Boolean = false)(implicit val p: Parameters) extends RawModule {
  require(numPorts > 0)
  val clock = RationalClockBridge().io.clocks.head
  val resetBridge = Module(new ResetPulseBridge(ResetPulseBridgeParameters()))
  resetBridge.io.clock := clock

  withClockAndReset(clock, resetBridge.io.reset) {
    val probes = Seq.tabulate(numPorts) { port =>
      val probe = Module(new FixedReadProbeDUT(
        p(NastiKey), requestsPerPort, BigInt(port) * requestsPerPort * 32, interleaveBanks,
      ))
      val fased = Module(new FASEDBridge(CompleteConfig(p(NastiKey))))
      fased.io.axi4 <> probe.io.nasti
      fased.io.reset := resetBridge.io.reset
      fased.io.clock := clock
      probe
    }
    PeekPokeBridge(
      clock,
      WireInit(false.B),
      ("done", probes.map(_.io.done).reduce(_ && _)),
      ("error", probes.map(_.io.error).reduce(_ || _)),
    )
  }
}

class FixedReadProbe(implicit p: Parameters) extends FixedReadProbeTop(1, 8192)

// Same 1 MiB / 32,768-read logical workload, with either one 8-byte AXI port
// or four independent ports. This isolates memory-model choice from the
// channel-count/topology effect that gives HBM its aggregate bandwidth.
class FixedReadProbe1M(implicit p: Parameters) extends FixedReadProbeTop(1, 32768)
class FixedReadProbe4(implicit p: Parameters) extends FixedReadProbeTop(4, 8192)

// Same 1 MiB address set and request count, but bank-interleaved line order.
// This isolates REFSB bank-local progress from a sequential stream that can
// fill the scheduler window with requests for a single blocked bank.
class FixedReadProbeInterleaved1M(implicit p: Parameters) extends FixedReadProbeTop(1, 32768, true)
class FixedReadProbeInterleaved4(implicit p: Parameters) extends FixedReadProbeTop(4, 8192, true)
