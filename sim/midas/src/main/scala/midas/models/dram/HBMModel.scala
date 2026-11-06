package midas
package models

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters

import junctions.NastiKey
import midas.widgets._

import firesim.lib.nasti._

/** =============================================================================
  * HBM2 (JESD235) memory timing model for FASED
  * =============================================================================
  *
  * Models `maxChannels` (default 1) fully independent HBM2 channels, each
  * operating in pseudo-channel (PC) mode with its own first-ready FCFS memory
  * access scheduler. Structurally this borrows from FirstReadyFCFSModel, with
  * the DDR3 rank/bank hierarchy replaced by the JESD235 hierarchy:
  *
  *   - Channels are the top of the JESD235 hierarchy and share *nothing*
  *     electrically: each channel has its own command buses, bank state,
  *     bank-group/PC timing trackers, refresh state, reference window,
  *     scheduler, and completion pipes (one HBMChannelScheduler instance per
  *     channel). The only shared structures are the AXI4 front-end
  *     transaction queue that dispatches decoded requests to their target
  *     channel (one request per cycle -- the input-bandwidth limit of the
  *     single AXI4 port) and the response arbiters that merge per-channel
  *     completions back onto the AXI4 R/B channels. Which address bits
  *     select the channel is runtime-programmable (chAddr); the default is
  *     a contiguous partition of the physical address space, with the
  *     channel index in the bits directly above one channel's capacity.
  *
  *   - Pseudo-channels take the structural place of ranks. Each PC has
  *     independent bank state and an independent data bus, so there is no
  *     rank-to-rank data-bus switching penalty (tRTRS) between PCs.
  *
  *   - The channel has *two* command buses, mirroring JESD235's split
  *     row (R[6:0]) and column (C[8:0]) buses: one row command (ACT / PRE /
  *     REF / REFSB) and one column command (RD / WR) may issue in the same
  *     cycle. An ACT occupies the row bus for two cycles (JESD235 ACTs are
  *     two-frame commands).
  *
  *   - Bank groups add the JESD235 short/long timing split. Same-bank-group
  *     accesses are constrained by the *long* parameters (tCCDL, tRRDL,
  *     tWTRL), different-bank-group accesses by the *short* ones (tCCDS,
  *     tRRDS, tWTRS).
  *
  *   - Row-to-column delay is asymmetric: tRCDRD for reads, tRCDWR for
  *     writes.
  *
  *   - The read-to-write turnaround is enforced at pseudo-channel scope as
  *     nCL + nBL + 2 - nCWL, per JESD235.
  *
  *   - Refresh is runtime-selectable between all-bank refresh (REFab,
  *     default) and per-bank refresh (REFSB). In REFSB mode one bank is
  *     refreshed per tREFI/16 interval in round-robin order; only the
  *     refreshed bank is blocked (for tRFCSB) while the other banks keep
  *     serving traffic. REFSB commands count toward the tFAW window and are
  *     spaced by tRREFD, per JESD235.
  *
  *   - Optionally (refsbIdleFirst), each owed REFSB goes to a bank of the
  *     current set that is idle instead of to the next bank in round-robin
  *     order, and up to refsbMaxPostponed owed REFSBs may wait for such a
  *     bank. JESD270-4A 6.3.2.5: REFpb may address the banks of a set in any
  *     order, a bank may not be refreshed again until its set completes, no
  *     REFpb may follow the last one of a set within tRFCpb, at most 9 x
  *     tREFI may pass between refreshes of one bank, and at most 9 REFpb may
  *     go to one bank within tREFI. refsbMaxPostponed is capped at six sets:
  *     a bank then waits at most (2 x banks - 1 + 6 x banks + 1) x tREFI /
  *     banks = 8 x tREFI, and a catch-up refreshes it at most 7 times within
  *     tREFI. refsbIdleAge additionally lets an open bank with no pending
  *     reference and no command for that many cycles take an owed REFSB
  *     (it is precharged first); the choice is dropped if a reference for
  *     the bank arrives before the REFSB issues. refsbYield ranks REFSBs
  *     that are not yet overdue, and their precharges, below demand ACT and
  *     PRE. refsbLRU picks, among the candidate banks, the one with the
  *     longest time since its last command instead of the lowest index.
  *     refsbAgeScale halves the refsbIdleAge threshold for each full set of
  *     owed REFSBs. refsbLRU picks the bank with the longest time since its last
  *     command, instead of the lowest index, whenever an open bank is chosen
  *     for refresh. refsbAgeScale halves the refsbIdleAge threshold for each
  *     full set of owed REFSBs.
  *
  *   - Optionally (bankXorHash), the bank index is XORed with the row
  *     address folded to the bank-index width (permutation-based page
  *     interleaving). For any one row this permutes the banks, so the
  *     address map stays one-to-one; streams whose bases differ only in row
  *     bits stop landing in the same bank.
  *
  * All timing parameters are runtime-programmable registers (in units of the
  * memory controller clock, like all FASED timings), so alternate timing
  * tables -- e.g. the PARE table -- can be loaded from a runtime config
  * without regenerating the FPGA image.
  */

/** Organization of the modeled HBM2 stack in pseudo-channel mode.
  *
  * @param maxPseudoChannels
  *   pseudo channels per channel (2 in HBM2 PC mode)
  * @param maxBankGroups
  *   bank groups per pseudo channel (4 in HBM2)
  * @param banksPerGroup
  *   banks per bank group (4 in HBM2)
  * @param channelSize
  *   bytes of addressable storage modeled *per channel*. Like
  *   DramOrganizationParams.dramSize this is an upper bound used to size
  *   address registers; the actual decode is runtime-programmable.
  * @param maxChannels
  *   independent HBM channels modeled by one HBMModel instance. Each channel
  *   gets its own scheduler and complete timing state (see HBMModel header).
  *   The total modeled capacity is maxChannels * channelSize.
  * @param sidBits
  *   stack-ID (SID) bits among the bank-group bits: the top sidBits of the
  *   model's bank-group index (JESD235D Table 5: 1 for 8-High stacks, whose
  *   32 banks are SID, BA[3:0] in groups A-D / E-H). Reads to a different SID
  *   are spaced by tCCDR. 0 = no SID.
  */
case class HBMOrganizationParams(
  maxPseudoChannels: Int,
  maxBankGroups:     Int,
  banksPerGroup:     Int,
  channelSize:       BigInt,
  maxChannels:       Int = 1,
  lineBits:          Int = 8,
  sidBits:           Int = 0,
) {
  require(isPow2(maxPseudoChannels) && maxPseudoChannels >= 2)
  require(isPow2(maxBankGroups))
  require(isPow2(banksPerGroup))
  require(isPow2(channelSize))
  require(isPow2(maxChannels) && maxChannels >= 1)
  require(sidBits >= 0 && sidBits < log2Up(maxBankGroups))
  def maxBanks   = maxBankGroups * banksPerGroup // banks per pseudo channel
  def bankBits   = log2Up(maxBanks)
  def bankGroupBits = log2Up(maxBankGroups)
  def pcBits     = log2Up(maxPseudoChannels)
  def rowBits    = log2Ceil(channelSize) - lineBits
  def maxRows    = 1 << rowBits
  def chBits     = log2Ceil(maxChannels) // 0 when single-channel
  def sidOfGroup(group: Int): Int = group >> (bankGroupBits - sidBits)
}

/** Width of HBM timing registers and of every state element that stores or
  * compares against them (DownCounters, the tFAW window queue, the truncated
  * tCycle used for tFAW bookkeeping, and the backend latency path).
  *
  * The stock DDR3 model uses maxDRAMTimingBits = 7, which is too narrow for
  * HBM: tRFCab @ tCK = 833 ps is already 421 CK, and alternate tables (e.g.
  * PARE) may program larger values into *any* field. 16 bits accommodates
  * every JESD235 timing at HBM2 clocks with ample headroom, and keeps all
  * modular-arithmetic comparisons (tCycle + t === tCycle') consistent as
  * long as every programmed timing fits in 16 bits.
  */
trait HasHBMTimingConstants {
  val hbmTimingBits = 16
}

/** Limits of the idle-first REFSB policy (see the HBMModel header). */
object HBMRefreshPolicy {
  def maxPostponed(key: HBMOrganizationParams): Int = 6 * key.maxBanks
  def maxPostponedBits(key: HBMOrganizationParams): Int = log2Ceil(maxPostponed(key) + 1)
  val idleAgeBits = 12
}

/** Named HBM2 timing tables, in units of the controller clock (CK).
  *
  * The HBM2 speed grades follow JESD235B/D; secondary parameters (tRRD, tFAW,
  * tRFC, tRFCSB, tRREFD, tREFI) are resolved from their ns specs at the
  * corresponding tCK (1250 ps / 1000 ps / 833 ps).
  *
  * PARE is a placeholder that currently aliases HBM2-2400: the real PARE
  * timing table should be dropped in here once it is provided.
  */
object HBMTimingTables {
  val hbm2_2400: Map[String, BigInt] = Map(
    "tCAS"   -> 17, // nCL
    "tCWD"   -> 6,  // nCWL
    "tBL"    -> 2,  // nBL (BL4 over DDR)
    "tCMD"   -> 1,
    "tCCDS"  -> 2,
    "tCCDL"  -> 4,
    "tRRDS"  -> 5,  // 4 ns @ 833 ps
    "tRRDL"  -> 5,
    "tFAW"   -> 19, // 15 ns
    "tWTRS"  -> 8,
    "tWTRL"  -> 10,
    "tRCDRD" -> 17,
    "tRCDWR" -> 14,
    "tRP"    -> 17,
    "tRAS"   -> 40,
    "tRC"    -> 57,
    "tRTP"   -> 6,  // nRTPL
    "tWR"    -> 19,
    "tREFI"  -> 4682, // 3.9 us
    "tRFC"   -> 421,  // 350 ns (8Gb per-PC density)
    "tRFCSB" -> 193,  // 160 ns (8Gb, per-bank refresh); ceil(160000/833)
    "tRREFD" -> 10,   // 8 ns
  ).map { case (k, v) => k -> BigInt(v) }

  val hbm2_2000: Map[String, BigInt] = Map(
    "tCAS"   -> 14,
    "tCWD"   -> 5,
    "tBL"    -> 2,
    "tCMD"   -> 1,
    "tCCDS"  -> 2,
    "tCCDL"  -> 4,
    "tRRDS"  -> 4,
    "tRRDL"  -> 4,
    "tFAW"   -> 15,
    "tWTRS"  -> 6,
    "tWTRL"  -> 8,
    "tRCDRD" -> 14,
    "tRCDWR" -> 12,
    "tRP"    -> 14,
    "tRAS"   -> 34,
    "tRC"    -> 48,
    "tRTP"   -> 5,
    "tWR"    -> 16,
    "tREFI"  -> 3900,
    "tRFC"   -> 350,
    "tRFCSB" -> 160,
    "tRREFD" -> 8,
  ).map { case (k, v) => k -> BigInt(v) }

  val hbm2_1600: Map[String, BigInt] = Map(
    "tCAS"   -> 10,
    "tCWD"   -> 4,
    "tBL"    -> 2,
    "tCMD"   -> 1,
    "tCCDS"  -> 2,
    "tCCDL"  -> 4,
    "tRRDS"  -> 4,
    "tRRDL"  -> 4,
    "tFAW"   -> 12,
    "tWTRS"  -> 5,
    "tWTRL"  -> 6,
    "tRCDRD" -> 10,
    "tRCDWR" -> 8,
    "tRP"    -> 10,
    "tRAS"   -> 24,
    "tRC"    -> 34,
    "tRTP"   -> 4,
    "tWR"    -> 12,
    "tREFI"  -> 3120,
    "tRFC"   -> 280,
    "tRFCSB" -> 128,
    "tRREFD" -> 7,
  ).map { case (k, v) => k -> BigInt(v) }

  // TODO(PARE): replace with the real PARE timing table when it is provided.
  val pare: Map[String, BigInt] = hbm2_2400

  // _1 = table, _2 = long name shown in interactive selection
  val grades = Seq(
    (hbm2_2400 -> "HBM2-2400 (JESD235, tCK = 833 ps)"),
    (hbm2_2000 -> "HBM2-2000 (JESD235, tCK = 1000 ps)"),
    (hbm2_1600 -> "HBM2-1600 (JESD235, tCK = 1250 ps)"),
    (pare      -> "PARE (PLACEHOLDER -- currently aliases HBM2-2400)"),
  )
}

/** Runtime-programmable HBM2 timing registers, in controller-clock units.
  * Defaults correspond to HBM2-2400 (the project reference table).
  */
class HBMProgrammableTimings
    extends Bundle
    with HasDRAMMASConstants
    with HasHBMTimingConstants
    with HasProgrammableRegisters
    with HasConsoleUtils {
  val tCAS   = UInt(hbmTimingBits.W)
  val tCWD   = UInt(hbmTimingBits.W)
  val tBL    = UInt(hbmTimingBits.W)
  val tCMD   = UInt(hbmTimingBits.W)
  val tCCDS  = UInt(hbmTimingBits.W)
  val tCCDL  = UInt(hbmTimingBits.W)
  val tRRDS  = UInt(hbmTimingBits.W)
  val tRRDL  = UInt(hbmTimingBits.W)
  val tFAW   = UInt(hbmTimingBits.W)
  val tWTRS  = UInt(hbmTimingBits.W)
  val tWTRL  = UInt(hbmTimingBits.W)
  val tRCDRD = UInt(hbmTimingBits.W)
  val tRCDWR = UInt(hbmTimingBits.W)
  val tRP    = UInt(hbmTimingBits.W)
  val tRAS   = UInt(hbmTimingBits.W)
  val tRC    = UInt(hbmTimingBits.W)
  val tRTP   = UInt(hbmTimingBits.W)
  val tWR    = UInt(hbmTimingBits.W)
  val tREFI  = UInt(hbmTimingBits.W)
  val tRFC   = UInt(hbmTimingBits.W)
  val tRFCSB = UInt(hbmTimingBits.W)
  val tRREFD = UInt(hbmTimingBits.W)

  val registers = Seq(
    tCAS   -> RuntimeSetting(17, "CAS (read) latency, nCL"),
    tCWD   -> RuntimeSetting(6, "Write latency, nCWL"),
    tBL    -> RuntimeSetting(2, "Burst duration in CK, nBL"),
    tCMD   -> RuntimeSetting(1, "Command transport time"),
    tCCDS  -> RuntimeSetting(2, "CAS-to-CAS delay, different bank group (tCCDS)"),
    tCCDL  -> RuntimeSetting(4, "CAS-to-CAS delay, same bank group (tCCDL)"),
    tRRDS  -> RuntimeSetting(5, "ACT-to-ACT delay, different bank group (tRRDS)"),
    tRRDL  -> RuntimeSetting(5, "ACT-to-ACT delay, same bank group (tRRDL)"),
    tFAW   -> RuntimeSetting(19, "Four-activate window (tFAW)"),
    tWTRS  -> RuntimeSetting(8, "Write-to-read turnaround, different bank group (tWTRS)"),
    tWTRL  -> RuntimeSetting(10, "Write-to-read turnaround, same bank group (tWTRL)"),
    tRCDRD -> RuntimeSetting(17, "ACT-to-read delay (tRCDRD)"),
    tRCDWR -> RuntimeSetting(14, "ACT-to-write delay (tRCDWR)"),
    tRP    -> RuntimeSetting(17, "Row precharge delay (tRP)"),
    tRAS   -> RuntimeSetting(40, "Row access strobe delay (tRAS)"),
    tRC    -> RuntimeSetting(57, "Row cycle time (tRC)"),
    tRTP   -> RuntimeSetting(6, "Read-to-precharge delay (tRTPL)"),
    tWR    -> RuntimeSetting(19, "Write recovery time (tWR)"),
    tREFI  -> RuntimeSetting(4682, "Refresh interval (tREFI); 0 disables refresh"),
    tRFC   -> RuntimeSetting(421, "Refresh cycle time, all-bank (tRFCab)"),
    tRFCSB -> RuntimeSetting(193, "Refresh cycle time, single-bank (tRFCSB)"),
    tRREFD -> RuntimeSetting(10, "REFSB-to-REFSB / ACT-to-REFSB delay (tRREFD)"),
  )

  def setTimingTable(table: Map[String, BigInt]): Unit = {
    registers.foreach { case (elem, reg) => reg.set(table(getName(elem))) }
  }
}

abstract class HBMBaseConfig extends BaseConfig with HasDRAMMASConstants {
  def hbmKey:     HBMOrganizationParams
  def backendKey: DRAMBackendKey
}

case class HBMModelConfig(
  hbmKey:                HBMOrganizationParams,
  schedulerWindowSize:   Int,
  transactionQueueDepth: Int,
  // hbmTimingBits-wide backend so (tCAS + backendLatency) cannot truncate in
  // the DynamicLatencyPipe when large timing tables are programmed at runtime.
  // Eight completion entries prevent the default four-entry backend from
  // limiting a 32-byte-CAS / 8-byte-per-cycle AXI port below its own bandwidth:
  // four reads / (tCAS 17 + backend 2) = 6.74 bytes/cycle. The backend is an
  // emulator completion resource, not an HBM2 device queue-depth parameter.
  backendKey:            DRAMBackendKey = DRAMBackendKey(8, 8, 16),
  // When true, print one machine-parseable "HBMREQ,..." line per memory
  // transaction accepted by the scheduler (metasim only; adds no state and
  // no backpressure). See HBMModel.requestTrace below for the format.
  requestTrace:          Boolean = false,
  params:                BaseParams,
) extends HBMBaseConfig {
  def elaborate()(implicit p: Parameters): HBMModel = Module(new HBMModel(this))
}

class HBMMMRegIO(val cfg: HBMModelConfig) extends MMRegIO(cfg) with HasConsoleUtils {
  // Default address layout, LSB to MSB:
  //   [ line offset (6b) | lines-per-row (4b) | bank (4b, group in low bits)
  //     | pseudo channel (1b) | row ]
  // This gives a 1 KiB page per (PC, bank) and interleaves sequential rows
  // across bank groups.
  val bankAddr = Input(
    new ProgrammableSubAddr(
      maskBits      = cfg.hbmKey.bankBits,
      longName      = "Bank Address",
      defaultOffset = 10,
      defaultMask   = cfg.hbmKey.maxBanks - 1,
    )
  )

  val pcAddr = Input(
    new ProgrammableSubAddr(
      maskBits      = cfg.hbmKey.pcBits,
      longName      = "Pseudo Channel Address",
      defaultOffset = 10 + cfg.hbmKey.bankBits,
      defaultMask   = cfg.hbmKey.maxPseudoChannels - 1,
    )
  )

  val defaultRowOffset = 10 + cfg.hbmKey.bankBits + cfg.hbmKey.pcBits
  val rowAddr = Input(
    new ProgrammableSubAddr(
      maskBits      = cfg.hbmKey.rowBits,
      longName      = "Row Address",
      defaultOffset = defaultRowOffset,
      defaultMask   = (cfg.hbmKey.channelSize >> defaultRowOffset) - 1,
    )
  )

  // Channel select for multi-channel configurations. Only materialized when
  // maxChannels > 1 so single-channel runtime configs remain valid with no
  // new registers to program. The default is a contiguous partition of the
  // address space: the channel index lives in the bits directly above one
  // channel's capacity; both fields are runtime-programmable, so any other
  // contiguous-bit-slice mapping (e.g. finer-grained partitions for small
  // test footprints) can be selected without regenerating the design.
  val chAddr = if (cfg.hbmKey.maxChannels > 1) {
    Some(
      Input(
        new ProgrammableSubAddr(
          maskBits      = cfg.hbmKey.chBits,
          longName      = "Channel Address",
          defaultOffset = log2Ceil(cfg.hbmKey.channelSize),
          defaultMask   = cfg.hbmKey.maxChannels - 1,
        )
      )
    )
  } else {
    None
  }

  // Page policy 1 = open, 0 = closed
  val openPagePolicy = Input(Bool())
  // Refresh mode 1 = per-bank (REFSB), 0 = all-bank (REFab)
  val perBankRefresh = Input(Bool())
  // REFSB bank order 1 = an idle bank of the current set first, 0 = round-robin
  val refsbIdleFirst = Input(Bool())
  // Owed REFSBs that may wait for an idle bank (refsbIdleFirst only)
  val refsbMaxPostponed = Input(UInt(HBMRefreshPolicy.maxPostponedBits(cfg.hbmKey).W))
  // Cycles without a command after which an open bank counts as idle for
  // REFSB (refsbIdleFirst only); 0 = only precharged banks are idle
  val refsbIdleAge = Input(UInt(HBMRefreshPolicy.idleAgeBits.W))
  // 1 = REFSBs that are not overdue yield the row bus to demand ACT / PRE
  val refsbYield = Input(Bool())
  // REFSB bank choice 1 = least recently used candidate, 0 = lowest index
  val refsbLRU = Input(Bool())
  // 1 = refsbIdleAge is halved for each full set of owed REFSBs
  val refsbAgeScale = Input(Bool())
  // Bank index 1 = XOR-hashed with the folded row address, 0 = plain bit slice
  val bankXorHash = Input(Bool())
  // Additional latency added to read data beats after they leave the devices
  val backendLatency = Input(UInt(cfg.backendKey.latencyBits.W))

  val hbmTimings = Input(new HBMProgrammableTimings())
  // Seamless RD-to-RD delay between stack IDs (JESD235D tCCDR: vendor specific,
  // 2 to 4 nCK). Only materialized when the organization has SID bits, so the
  // register set of every other configuration is unchanged.
  val tCCDR = if (cfg.hbmKey.sidBits > 0) Some(Input(UInt(16.W))) else None

  val schedulerWindowSize   = Input(UInt(log2Ceil(cfg.schedulerWindowSize).W))
  val transactionQueueDepth = Input(UInt(log2Ceil(cfg.transactionQueueDepth).W))

  val registers = Seq(
    openPagePolicy        -> RuntimeSetting(1, "Open-Page Policy"),
    perBankRefresh        -> RuntimeSetting(0, "Per-bank refresh (1 = REFSB, 0 = all-bank REFab)"),
    backendLatency        -> RuntimeSetting(
      2,
      "Backend Latency",
      min = 1,
      max = Some(1 << (cfg.backendKey.latencyBits - 1)),
    ),
    schedulerWindowSize   -> RuntimeSetting(
      default = cfg.schedulerWindowSize,
      query   = "Reference queue depth",
      min     = 1,
      max     = Some(cfg.schedulerWindowSize),
    ),
    transactionQueueDepth -> RuntimeSetting(
      default = cfg.transactionQueueDepth,
      query   = "Transaction queue depth",
      min     = 1,
      max     = Some(cfg.transactionQueueDepth),
    ),
    refsbIdleFirst        -> RuntimeSetting(0, "REFSB to an idle bank first (1) or round-robin (0)"),
    refsbMaxPostponed     -> RuntimeSetting(
      0,
      "REFSBs that may be postponed for an idle bank",
      max = Some(HBMRefreshPolicy.maxPostponed(cfg.hbmKey)),
    ),
    refsbIdleAge          -> RuntimeSetting(
      0,
      "Idle cycles before an open bank may take an owed REFSB (0 = precharged banks only)",
      max = Some((1 << HBMRefreshPolicy.idleAgeBits) - 1),
    ),
    refsbYield            -> RuntimeSetting(0, "REFSBs that are not overdue yield to demand ACT / PRE"),
    refsbLRU              -> RuntimeSetting(0, "REFSB to the least recently used candidate bank (1) or the lowest index (0)"),
    refsbAgeScale         -> RuntimeSetting(0, "Halve the REFSB idle age for each full set owed"),
    bankXorHash           -> RuntimeSetting(0, "XOR the folded row address into the bank index"),
  ) ++ tCCDR.map(_ -> RuntimeSetting(2, "RD-to-RD delay between stack IDs (tCCDR)", min = 1))

  def requestSettings(): Unit = {
    Console.println("Configuring an HBM2 (JESD235) First-Ready FCFS model")
    val gradeIdx = requestSeqSelection(
      "Select an HBM timing table:",
      HBMTimingTables.grades.map(_._2),
    )
    hbmTimings.setTimingTable(HBMTimingTables.grades(gradeIdx)._1)
  }
}

// Scheduler entry: a decoded memory reference with HBM coordinates
class HBMEntry(nastiParams: NastiParameters, cfg: HBMBaseConfig)(implicit p: Parameters) extends Bundle {
  val xaction   = new TransactionMetaData(nastiParams)
  val rowAddr   = UInt(cfg.hbmKey.rowBits.W)
  // Bank address within a pseudo channel; the bank group lives in the low
  // bankGroupBits so that sequential row buffers interleave across groups.
  val bankAddr   = UInt(cfg.hbmKey.bankBits.W)
  val bankAddrOH = UInt(cfg.hbmKey.maxBanks.W)
  val pcAddr     = UInt(cfg.hbmKey.pcBits.W)
  val pcAddrOH   = UInt(cfg.hbmKey.maxPseudoChannels.W)

  val isReady = Bool() // Set when this entry hits in an open row buffer
  val mayPRE  = Bool() // Set when no other entries hit the same open row

  def bankGroupAddr:   UInt = bankAddr(cfg.hbmKey.bankGroupBits - 1, 0)
  def bankGroupAddrOH: UInt = UIntToOH(bankGroupAddr)

  def decode(from: XactionSchedulerEntry, mmReg: HBMMMRegIO): Unit = {
    val row  = WireInit(UInt(cfg.hbmKey.rowBits.W), mmReg.rowAddr.getSubAddr(from.addr))
    val bank = WireInit(UInt(cfg.hbmKey.bankBits.W), mmReg.bankAddr.getSubAddr(from.addr))
    xaction    := from.xaction
    bankAddr   := Mux(mmReg.bankXorHash, bank ^ RowFold(row, cfg.hbmKey.bankBits), bank)
    bankAddrOH := UIntToOH(bankAddr)
    rowAddr    := row
    pcAddr     := mmReg.pcAddr.getSubAddr(from.addr)
    pcAddrOH   := UIntToOH(pcAddr)
  }

  def addrMatch(pc: UInt, bank: UInt, row: Option[UInt] = None): Bool = {
    val rowHit = row.foldLeft(true.B)({ case (p, addr) => p && addr === rowAddr })
    pc === pcAddr && bank === bankAddr && rowHit
  }

  def wantPRE(): Bool = !isReady && mayPRE
  def wantACT(): Bool = !isReady
}

// ============================================================================
// Bank state tracker
// ============================================================================
// Differences from the DDR3 BankStateTracker:
//   - split read/write column-access legality (tRCDRD vs tRCDWR)
//   - write recovery uses the explicit burst duration tBL
//   - REFSB support: a per-bank refresh occupies (only) this bank for tRFCSB

class HBMBankStateTrackerO(val key: HBMOrganizationParams) extends Bundle {
  import DRAMMasEnums._
  val canCASR = Output(Bool())
  val canCASW = Output(Bool())
  val canPRE  = Output(Bool())
  val canACT  = Output(Bool())
  val openRow = Output(UInt(key.rowBits.W))
  val state   = Output(Bool())

  def isRowHit(ref: HBMEntry): Bool = ref.rowAddr === openRow && state === bank_active
}

class HBMBankStateTrackerIO(val key: HBMOrganizationParams) extends Bundle {
  import DRAMMasEnums._
  val timings         = Input(new HBMProgrammableTimings)
  val selectedCmd     = Input(cmd_nop.cloneType)
  val autoPRE         = Input(Bool())
  val cmdRow          = Input(UInt(key.rowBits.W))
  val out             = new HBMBankStateTrackerO(key)
  val cmdUsesThisBank = Input(Bool())
}

class HBMBankStateTracker(key: HBMOrganizationParams)
    extends Module
    with HasDRAMMASConstants
    with HasHBMTimingConstants {
  import DRAMMasEnums._
  val io = IO(new HBMBankStateTrackerIO(key))

  val state       = RegInit(bank_idle)
  val openRowAddr = Reg(UInt(key.rowBits.W))

  val nextLegalPRE  = Module(new DownCounter(hbmTimingBits))
  val nextLegalACT  = Module(new DownCounter(hbmTimingBits))
  val nextLegalCASR = Module(new DownCounter(hbmTimingBits))
  val nextLegalCASW = Module(new DownCounter(hbmTimingBits))

  Seq(nextLegalPRE, nextLegalACT, nextLegalCASR, nextLegalCASW).foreach { mod =>
    mod.io.decr      := true.B
    mod.io.set.valid := false.B
    mod.io.set.bits  := DontCare
  }

  when(io.cmdUsesThisBank) {
    switch(io.selectedCmd) {
      is(cmd_act) {
        assert(io.out.canACT, "HBM Bank Timing Violation: Controller issued ACT command illegally")
        // JESD235 ACT is a two-frame command; the model issues (and stamps)
        // it on its first frame, but the DRAM registers it on the second.
        // ACT-relative bank timings therefore get +1 relative to the issue
        // cycle: RD at +(tRCDRD+1), WR at +(tRCDWR+1), PRE at +(tRAS+1).
        // ACT-to-ACT spacings (tRC here, tRRD/tFAW in the PC tracker) are
        // measured second-frame to second-frame, so the offsets cancel;
        // tRC is nonetheless programmed un-decremented so that the
        // REF/REFSB gate (canACT) also observes ACT-to-REF = tRC + 1,
        // making same-bank ACT-to-ACT conservative by one cycle.
        state                      := bank_active
        openRowAddr                := io.cmdRow
        nextLegalCASR.io.set.valid := true.B
        nextLegalCASR.io.set.bits  := io.timings.tRCDRD
        nextLegalCASW.io.set.valid := true.B
        nextLegalCASW.io.set.bits  := io.timings.tRCDWR
        nextLegalPRE.io.set.valid  := true.B
        nextLegalPRE.io.set.bits   := io.timings.tRAS
        nextLegalACT.io.set.valid  := true.B
        nextLegalACT.io.set.bits   := io.timings.tRC
      }
      is(cmd_casr) {
        assert(io.out.canCASR, "HBM Bank Timing Violation: Controller issued CASR command illegally")
        when(io.autoPRE) {
          state                     := bank_idle
          nextLegalACT.io.set.valid := true.B
          nextLegalACT.io.set.bits  := io.timings.tRTP + io.timings.tRP - 1.U
        }.otherwise {
          nextLegalPRE.io.set.valid := true.B
          nextLegalPRE.io.set.bits  := io.timings.tRTP - 1.U
        }
      }
      is(cmd_casw) {
        assert(io.out.canCASW, "HBM Bank Timing Violation: Controller issued CASW command illegally")
        when(io.autoPRE) {
          state                     := bank_idle
          nextLegalACT.io.set.valid := true.B
          nextLegalACT.io.set.bits  := io.timings.tCWD + io.timings.tBL + io.timings.tWR +
            io.timings.tRP - 1.U
        }.otherwise {
          nextLegalPRE.io.set.valid := true.B
          nextLegalPRE.io.set.bits  := io.timings.tCWD + io.timings.tBL + io.timings.tWR - 1.U
        }
      }
      is(cmd_pre) {
        assert(io.out.canPRE, "HBM Bank Timing Violation: Controller issued PRE command illegally")
        state                     := bank_idle
        nextLegalACT.io.set.valid := true.B
        nextLegalACT.io.set.bits  := io.timings.tRP - 1.U
      }
      is(cmd_refsb) {
        assert(io.out.canACT, "HBM Bank Timing Violation: Controller issued REFSB to a non-idle bank")
        // Bank stays idle; it is simply unavailable for tRFCSB
        nextLegalACT.io.set.valid := true.B
        nextLegalACT.io.set.bits  := io.timings.tRFCSB - 1.U
      }
    }
  }

  io.out.canCASR := (state === bank_active) && nextLegalCASR.io.idle // Controller must check rowAddr
  io.out.canCASW := (state === bank_active) && nextLegalCASW.io.idle // Controller must check rowAddr
  io.out.canPRE  := (state === bank_active) && nextLegalPRE.io.idle
  io.out.canACT  := (state === bank_idle) && nextLegalACT.io.idle
  io.out.state   := state
  io.out.openRow := openRowAddr
}

// ============================================================================
// Pseudo-channel state tracker
// ============================================================================
// Owns:
//   - per-bank state (HBMBankStateTracker instances)
//   - per-bank-group CAS/ACT legality (long timings: tCCDL, tRRDL, tWTRL)
//   - pseudo-channel-scope CAS/ACT legality (short timings: tCCDS, tRRDS,
//     tWTRS; read-write turnarounds; tFAW)
//   - refresh state: all-bank (tREFI/tRFCab) or per-bank (tREFI/16, tRFCSB,
//     tRREFD, round-robin bank pointer)
//
// The tracker sees both command buses: one row command (ACT/PRE/REF/REFSB)
// and one column command (CASR/CASW) may target this PC in the same cycle,
// as long as they target different banks (the model's scheduler guarantees
// this). There are no cross-PC timing constraints: pseudo channels have
// separate data buses and (in this model) separate row/column bus slots.

class HBMPseudoChannelStateTrackerO(val key: HBMOrganizationParams) extends Bundle {
  import DRAMMasEnums._
  val canCASR   = Output(Bool())
  val canCASW   = Output(Bool())
  val canACT    = Output(Bool())
  val canPRE    = Output(Bool())
  val canREF    = Output(Bool())
  val canREFSB  = Output(Bool())
  val wantREF   = Output(Bool())
  val refsbBank = Output(UInt(key.bankBits.W))
  // The wanted REFSB is not overdue and may wait behind demand ACT / PRE
  val refYields = Output(Bool())
  val state     = Output(rank_active.cloneType)
  val bgCanCASR = Vec(key.maxBankGroups, Output(Bool()))
  val bgCanCASW = Vec(key.maxBankGroups, Output(Bool()))
  val bgCanACT  = Vec(key.maxBankGroups, Output(Bool()))
  val banks     = Vec(key.maxBanks, Output(new HBMBankStateTrackerO(key)))
}

class HBMPseudoChannelStateTrackerIO(val key: HBMOrganizationParams)
    extends Bundle
    with HasDRAMMASConstants
    with HasHBMTimingConstants {
  import DRAMMasEnums._
  val timings          = Input(new HBMProgrammableTimings)
  val tCCDR            = if (key.sidBits > 0) Some(Input(UInt(hbmTimingBits.W))) else None
  val perBankRefresh   = Input(Bool())
  val refsbIdleFirst   = Input(Bool())
  val refsbMaxPostponed = Input(UInt(HBMRefreshPolicy.maxPostponedBits(key).W))
  val refsbIdleAge     = Input(UInt(HBMRefreshPolicy.idleAgeBits.W))
  val refsbYield       = Input(Bool())
  val refsbLRU         = Input(Bool())
  val refsbAgeScale    = Input(Bool())
  // Banks of this PC with a pending reference in the scheduler window
  val bankBusy         = Input(UInt(key.maxBanks.W))
  // Split command buses: row commands (ACT/PRE/REF/REFSB) and column
  // commands (CASR/CASW) may arrive in the same cycle.
  val rowCmd           = Input(cmd_nop.cloneType)
  val colCmd           = Input(cmd_nop.cloneType)
  val rowCmdUsesThisPC = Input(Bool())
  val colCmdUsesThisPC = Input(Bool())
  val rowBankOH        = Input(UInt(key.maxBanks.W))
  val colBankOH        = Input(UInt(key.maxBanks.W))
  val rowBankGroupOH   = Input(UInt(key.maxBankGroups.W))
  val colBankGroupOH   = Input(UInt(key.maxBankGroups.W))
  val cmdRow           = Input(UInt(key.rowBits.W))
  val autoPRE          = Input(Bool())
  val pc               = new HBMPseudoChannelStateTrackerO(key)
  // Truncated view of the model's 64-bit cycle counter, used only for
  // modular tFAW bookkeeping (tCycle + tFAW === tCycle', valid because
  // every timing fits in hbmTimingBits).
  val tCycle           = Input(UInt(hbmTimingBits.W))
}

class HBMPseudoChannelStateTracker(key: HBMOrganizationParams)
    extends Module
    with HasDRAMMASConstants
    with HasHBMTimingConstants {
  import DRAMMasEnums._

  val io = IO(new HBMPseudoChannelStateTrackerIO(key))
  val t  = io.timings

  // Pseudo-channel-scope legality (short / cross-bank-group constraints)
  val nextLegalPRE   = Module(new DownCounter(hbmTimingBits))
  val nextLegalACT   = Module(new DownCounter(hbmTimingBits))
  val nextLegalCASR  = Module(new DownCounter(hbmTimingBits))
  val nextLegalCASW  = Module(new DownCounter(hbmTimingBits))
  val nextLegalREFSB = Module(new DownCounter(hbmTimingBits))

  // Bank-group-scope legality (long / same-bank-group constraints)
  val bgNextLegalCASR = Seq.fill(key.maxBankGroups)(Module(new DownCounter(hbmTimingBits)))
  val bgNextLegalCASW = Seq.fill(key.maxBankGroups)(Module(new DownCounter(hbmTimingBits)))
  val bgNextLegalACT  = Seq.fill(key.maxBankGroups)(Module(new DownCounter(hbmTimingBits)))

  val refCounter = RegInit(0.U(hbmTimingBits.W))
  val state      = RegInit(rank_active)
  val wantREF    = RegInit(false.B)
  val refsbBank  = RegInit(0.U(key.bankBits.W))

  (Seq(nextLegalPRE, nextLegalACT, nextLegalCASR, nextLegalCASW, nextLegalREFSB) ++
    bgNextLegalCASR ++ bgNextLegalCASW ++ bgNextLegalACT).foreach { mod =>
    mod.io.decr      := true.B
    mod.io.set.valid := false.B
    mod.io.set.bits  := DontCare
  }

  // tFAW: at most 4 activates per PC in any tFAW window. REFSB commands
  // count toward the window (JESD235).
  val actLike   = io.rowCmdUsesThisPC && (io.rowCmd === cmd_act || io.rowCmd === cmd_refsb)
  val tFAWcheck = Module(new Queue(io.tCycle.cloneType, entries = 4))
  tFAWcheck.io.enq.valid := actLike
  tFAWcheck.io.enq.bits  := io.tCycle + t.tFAW
  tFAWcheck.io.deq.ready := io.tCycle === tFAWcheck.io.deq.bits

  // ------------------------------------------------------------------
  // Column-bus command effects (CASR / CASW)
  // ------------------------------------------------------------------
  when(io.colCmdUsesThisPC && io.colCmd === cmd_casr) {
    assert(io.pc.canCASR, "HBM PC Timing Violation: Controller issued CASR command illegally")
    // RD-to-RD: tCCDS across groups, tCCDL within the group
    nextLegalCASR.io.set.valid := true.B
    nextLegalCASR.io.set.bits  := t.tCCDS - 1.U
    // RD-to-WR turnaround, PC scope (JESD235): nCL + nBL + 2 - nCWL
    nextLegalCASW.io.set.valid := true.B
    nextLegalCASW.io.set.bits  := t.tCAS + t.tBL + 2.U - t.tCWD - 1.U
    bgNextLegalCASR.zip(io.colBankGroupOH.asBools).foreach { case (bg, sel) =>
      when(sel) {
        bg.io.set.valid := true.B
        bg.io.set.bits  := t.tCCDL - 1.U
      }
    }
    bgNextLegalCASW.zip(io.colBankGroupOH.asBools).foreach { case (bg, sel) =>
      when(sel) {
        bg.io.set.valid := true.B
        bg.io.set.bits  := t.tCCDL - 1.U
      }
    }
    // RD-to-RD between stack IDs: tCCDR (JESD235D Table 68 note 22; writes keep tCCDS)
    io.tCCDR.foreach { tCCDR =>
      val colGroups = io.colBankGroupOH.asBools
      bgNextLegalCASR.zipWithIndex.foreach { case (bg, g) =>
        val otherSID = colGroups.zipWithIndex
          .collect { case (sel, h) if key.sidOfGroup(h) != key.sidOfGroup(g) => sel }
          .reduce(_ || _)
        when(otherSID) {
          bg.io.set.valid := true.B
          bg.io.set.bits  := tCCDR - 1.U
        }
      }
    }
  }.elsewhen(io.colCmdUsesThisPC && io.colCmd === cmd_casw) {
    assert(io.pc.canCASW, "HBM PC Timing Violation: Controller issued CASW command illegally")
    // WR-to-WR: tCCDS across groups, tCCDL within the group
    nextLegalCASW.io.set.valid := true.B
    nextLegalCASW.io.set.bits  := t.tCCDS - 1.U
    // WR-to-RD turnaround: tWTRS across groups, tWTRL within the group
    nextLegalCASR.io.set.valid := true.B
    nextLegalCASR.io.set.bits  := t.tCWD + t.tBL + t.tWTRS - 1.U
    bgNextLegalCASR.zip(io.colBankGroupOH.asBools).foreach { case (bg, sel) =>
      when(sel) {
        bg.io.set.valid := true.B
        bg.io.set.bits  := t.tCWD + t.tBL + t.tWTRL - 1.U
      }
    }
    bgNextLegalCASW.zip(io.colBankGroupOH.asBools).foreach { case (bg, sel) =>
      when(sel) {
        bg.io.set.valid := true.B
        bg.io.set.bits  := t.tCCDL - 1.U
      }
    }
  }

  // ------------------------------------------------------------------
  // Row-bus command effects (ACT / PRE / REF / REFSB)
  // ------------------------------------------------------------------
  when(io.rowCmdUsesThisPC) {
    switch(io.rowCmd) {
      is(cmd_act) {
        assert(io.pc.canACT, "HBM PC Timing Violation: Controller issued ACT command illegally")
        // Different bank group: tRRDS; same bank group: tRRDL
        nextLegalACT.io.set.valid := true.B
        nextLegalACT.io.set.bits  := t.tRRDS - 1.U
        // ACT-to-REFSB spacing (tRREFD)
        nextLegalREFSB.io.set.valid := true.B
        nextLegalREFSB.io.set.bits  := t.tRREFD - 1.U
        bgNextLegalACT.zip(io.rowBankGroupOH.asBools).foreach { case (bg, sel) =>
          when(sel) {
            bg.io.set.valid := true.B
            bg.io.set.bits  := t.tRRDL - 1.U
          }
        }
      }
      is(cmd_pre) {
        assert(io.pc.canPRE, "HBM PC Timing Violation: Controller issued PRE command illegally")
      }
      is(cmd_ref) {
        assert(io.pc.canREF, "HBM PC Timing Violation: Controller issued REF command illegally")
        wantREF                   := false.B
        state                     := rank_refresh
        nextLegalACT.io.set.valid := true.B
        nextLegalACT.io.set.bits  := t.tRFC - 1.U
      }
      is(cmd_refsb) {
        assert(io.pc.canREFSB, "HBM PC Timing Violation: Controller issued REFSB command illegally")
        wantREF   := false.B
        refsbBank := refsbBank + 1.U
        // REFSB-to-REFSB and REFSB-to-ACT spacing (tRREFD)
        nextLegalREFSB.io.set.valid := true.B
        nextLegalREFSB.io.set.bits  := t.tRREFD - 1.U
        nextLegalACT.io.set.valid   := true.B
        nextLegalACT.io.set.bits    := t.tRREFD - 1.U
      }
    }
  }

  // Refresh scheduling. In REFab mode one refresh is owed per tREFI; in
  // REFSB mode one (single-bank) refresh is owed per tREFI / maxBanks.
  // Disable refresh by setting tREFI = 0.
  val refInterval = Mux(io.perBankRefresh, t.tREFI >> key.bankBits, t.tREFI)
  val refDue      = refCounter >= refInterval && t.tREFI =/= 0.U
  when(refDue) {
    refCounter := 0.U
    wantREF    := true.B
  }.otherwise {
    refCounter := refCounter + 1.U
  }

  when(state === rank_refresh && nextLegalACT.io.current === 1.U) {
    state := rank_active
  }

  val bankTrackers = Seq.fill(key.maxBanks)(Module(new HBMBankStateTracker(key)).io)
  io.pc.banks.zip(bankTrackers).foreach { case (out, bank) => out := bank.out }

  bankTrackers.zipWithIndex.foreach { case (bank, i) =>
    val colHit = io.colCmdUsesThisPC && io.colBankOH(i)
    val rowHit = io.rowCmdUsesThisPC && io.rowBankOH(i)
    assert(
      !(colHit && rowHit && io.colCmd =/= cmd_nop && io.rowCmd =/= cmd_nop),
      "HBM PC Tracker: row and column commands targeted the same bank in the same cycle",
    )
    bank.timings         := t
    bank.selectedCmd     := Mux(colHit, io.colCmd, Mux(rowHit, io.rowCmd, cmd_nop))
    bank.cmdUsesThisBank := colHit || rowHit
    bank.cmdRow          := io.cmdRow
    bank.autoPRE         := io.autoPRE
  }

  // Idle-bank-first REFSB. Owed refreshes accrue at the round-robin rate;
  // each goes to a bank of the current set that is precharged, ACT-legal,
  // and has no pending reference. Failing that, an open bank without pending
  // references and without a command for refsbIdleAge cycles (if nonzero) is
  // latched and precharged for refresh; the latch is dropped if a reference
  // for that bank arrives while the refresh is not yet overdue. Once more
  // than refsbMaxPostponed are owed and no precharged idle bank exists, one
  // unrefreshed bank (preferably an aged one, then one without pending
  // references) is latched and precharged for refresh, as in round-robin mode.
  // refsbLRU replaces the lowest-index choice among candidates with the least
  // recently used one; refsbAgeScale halves the idle age per full set owed.
  val idleFirst   = io.perBankRefresh && io.refsbIdleFirst
  val refsbIssued = io.rowCmdUsesThisPC && io.rowCmd === cmd_refsb
  val refsbOwed   = RegInit(0.U((HBMRefreshPolicy.maxPostponedBits(key) + 1).W))
  val refsbDone   = RegInit(0.U(key.maxBanks.W)) // banks refreshed in the current set
  val forcing     = RegInit(false.B)
  val forcedAged  = RegInit(false.B) // latched for being idle, not for being overdue
  val forcedBank  = RegInit(0.U(key.bankBits.W))

  // Cycles since the last command to each bank (saturating)
  val bankAge = Seq.tabulate(key.maxBanks) { i =>
    val age     = RegInit(0.U(HBMRefreshPolicy.idleAgeBits.W))
    val touched = (io.colCmdUsesThisPC && io.colBankOH(i)) || (io.rowCmdUsesThisPC && io.rowBankOH(i))
    when(touched) {
      age := 0.U
    }.elsewhen(!age.andR) {
      age := age + 1.U
    }
    age
  }

  // Index of the candidate with the largest age, the lowest index on ties
  def oldestOf(mask: UInt): UInt = {
    def pick(xs: Seq[(Bool, UInt, UInt)]): (Bool, UInt, UInt) =
      if (xs.size == 1) xs.head
      else {
        val (lv, la, li) = pick(xs.take(xs.size / 2))
        val (rv, ra, ri) = pick(xs.drop(xs.size / 2))
        val takeR        = rv && (!lv || ra > la)
        (lv || rv, Mux(takeR, ra, la), Mux(takeR, ri, li))
      }
    pick(bankAge.zipWithIndex.map { case (a, i) => (mask(i), a, i.U(key.bankBits.W)) })._3
  }
  def chooseBank(mask: UInt): UInt = Mux(io.refsbLRU, oldestOf(mask), PriorityEncoder(mask))

  val bankCanACT  = VecInit(bankTrackers.map { _.out.canACT }).asUInt
  val unrefreshed = ~refsbDone
  val idleBanks   = unrefreshed & bankCanACT & ~io.bankBusy
  val quietBanks  = unrefreshed & ~io.bankBusy
  val setsOwed    = refsbOwed >> key.bankBits
  val ageShift    = Mux(
    io.refsbAgeScale,
    Mux(setsOwed >= HBMRefreshPolicy.idleAgeBits.U, HBMRefreshPolicy.idleAgeBits.U, setsOwed),
    0.U,
  )
  val ageLimit    = io.refsbIdleAge >> ageShift
  val agedBanks   = Mux(
    io.refsbIdleAge === 0.U,
    0.U,
    quietBanks & VecInit(bankAge.map { _ >= ageLimit }).asUInt,
  )
  val urgent      = refsbOwed > io.refsbMaxPostponed
  val idleBank    = chooseBank(idleBanks)
  val flexBank    = Mux(idleBanks.orR, idleBank, forcedBank)
  val flexWant    = refsbOwed =/= 0.U && idleBanks.orR && nextLegalREFSB.io.idle || forcing
  val forcedBusy  = (io.bankBusy & UIntToOH(forcedBank, key.maxBanks)).orR

  when(idleFirst) {
    refsbOwed := refsbOwed + (refDue && !refsbOwed.andR).asUInt - refsbIssued.asUInt
    when(refsbIssued) {
      forcing := false.B
      val done = refsbDone | io.rowBankOH
      refsbDone := Mux(done.andR, 0.U, done)
      // No REFpb within tRFCpb after the last REFpb of a set
      when(done.andR) {
        nextLegalREFSB.io.set.valid := true.B
        nextLegalREFSB.io.set.bits  := t.tRFCSB - 1.U
      }
    }.elsewhen(forcing) {
      when(forcedAged && forcedBusy) {
        when(urgent) {
          forcedAged := false.B
        }.otherwise {
          forcing := false.B
        }
      }
    }.elsewhen(!idleBanks.orR) {
      when(urgent) {
        forcing    := true.B
        forcedAged := false.B
        forcedBank := chooseBank(
          Mux(agedBanks.orR, agedBanks, Mux(quietBanks.orR, quietBanks, unrefreshed))
        )
      }.elsewhen(refsbOwed =/= 0.U && agedBanks.orR) {
        forcing    := true.B
        forcedAged := true.B
        forcedBank := chooseBank(agedBanks)
      }
    }
  }

  val selRefsbBank    = Mux(idleFirst, flexBank, refsbBank)
  val refsbBankCanACT = Mux1H(UIntToOH(selRefsbBank), bankTrackers.map { _.out.canACT })

  io.pc.canREF    := bankTrackers.map { _.out.canACT }.reduce { _ && _ }
  io.pc.canREFSB  := nextLegalREFSB.io.idle && refsbBankCanACT && tFAWcheck.io.enq.ready
  io.pc.refsbBank := selRefsbBank
  io.pc.refYields := idleFirst && io.refsbYield && !urgent && !(forcing && !forcedAged)
  io.pc.canCASR   := nextLegalCASR.io.idle
  io.pc.canCASW   := nextLegalCASW.io.idle
  io.pc.canPRE    := nextLegalPRE.io.idle
  io.pc.canACT    := nextLegalACT.io.idle && tFAWcheck.io.enq.ready
  io.pc.wantREF   := Mux(idleFirst, flexWant, wantREF)
  io.pc.state     := state

  io.pc.bgCanCASR.zip(bgNextLegalCASR).foreach { case (out, ctr) => out := ctr.io.idle }
  io.pc.bgCanCASW.zip(bgNextLegalCASW).foreach { case (out, ctr) => out := ctr.io.idle }
  io.pc.bgCanACT.zip(bgNextLegalACT).foreach { case (out, ctr) => out := ctr.io.idle }
}

/** If this FASED instance's AXI slave is address-striped, the fixed low bits
  * of that address set are the memory-port index. A contiguous slave (the
  * historical one-channel edge) has no such hole and returns None, so command
  * traces stay byte-identical.
  *
  * Four independent FASED instances of a 1-channel HBMModel, each tagged this
  * way, are four channels of one logical HBM: FireSim groups them by
  * memoryRegionName onto one functional DRAM. They are not four separate
  * devices.
  */
object FasedStripePort {
  def apply()(implicit p: Parameters): Option[Int] =
    p(FasedAXI4Edge).flatMap(_.address.headOption).flatMap { as =>
      val width = as.mask.bitLength
      if (width <= 0 || as.mask < 0) None
      else {
        val hole = (~as.mask) & ((BigInt(1) << width) - 1)
        if (hole == 0) None
        else Some(((as.base & hole) >> hole.lowestSetBit).toInt)
      }
    }
}

/** Base address of this FASED instance's AXI slave, which tells apart bridges that share a console. */
object FasedEdgeBase {
  def apply()(implicit p: Parameters): Option[BigInt] =
    p(FasedAXI4Edge).flatMap(_.address.headOption).map(_.base)
}

// ============================================================================
// Per-channel scheduler
// ============================================================================
// One fully independent HBM channel: its own reference window, FR-FCFS
// column/row command scheduling, pseudo-channel / bank-group / bank timing
// state, refresh state, command buses, command-bus monitors, and completion
// latency pipes (DRAMBackend).
//
// Future PARE seam (not implemented): PARE will sit in front of this module
// and issue row/column DRAM commands itself. The transaction queue, FR-FCFS
// reference window, ACT/PRE/RD/WR selection, and refresh command arbitration
// in this scheduler are the pieces that move out. Bank/BG/PC timing counters,
// the tFAW window, and the command-legality checks stay as the HBM device
// model. Until that exists, this scheduler is the memory controller.
//
// The aggregate multi-channel HBMModel still shares one AXI frontend. The
// Radiance 4-path integration does not use that frontend: each L2/MC port
// gets its own 1-channel FASED instance so the paths are not serialized.

class HBMChannelSchedulerIO(val cfg: HBMModelConfig)(implicit val p: Parameters) extends Bundle {
  // Decoded reference dispatched by the shared front-end; only asserted
  // valid when this channel is the reference's decode target.
  val newReference        = Flipped(Decoupled(new HBMEntry(p(NastiKey), cfg)))
  // Runtime-programmable settings, shared across channels (one register
  // file programs all channels identically, like ranks in the DDR3 model).
  val timings             = Input(new HBMProgrammableTimings)
  val tCCDR               = if (cfg.hbmKey.sidBits > 0) Some(Input(UInt(16.W))) else None
  val openPagePolicy      = Input(Bool())
  val perBankRefresh      = Input(Bool())
  val refsbIdleFirst      = Input(Bool())
  val refsbMaxPostponed   = Input(UInt(HBMRefreshPolicy.maxPostponedBits(cfg.hbmKey).W))
  val refsbIdleAge        = Input(UInt(HBMRefreshPolicy.idleAgeBits.W))
  val refsbYield          = Input(Bool())
  val refsbLRU            = Input(Bool())
  val refsbAgeScale       = Input(Bool())
  val schedulerWindowSize = Input(UInt(log2Ceil(cfg.schedulerWindowSize).W))
  val pcsInUse            = Input(UInt(cfg.hbmKey.maxPseudoChannels.W))
  val backendLatency      = Input(UInt(cfg.backendKey.latencyBits.W))
  val tCycle              = Input(UInt(64.W))
  // Completed transactions, to be merged onto the AXI4 response channels
  val completedRead       = Decoupled(new ReadResponseMetaData(p(NastiKey)))
  val completedWrite      = Decoupled(new WriteResponseMetaData(p(NastiKey)))
}

class HBMChannelScheduler(cfg: HBMModelConfig, chIdx: Int)(implicit p: Parameters)
    extends Module
    with HasDRAMMASConstants {

  import DRAMMasEnums._
  val io = IO(new HBMChannelSchedulerIO(cfg))

  val hbmKey  = cfg.hbmKey
  val timings = io.timings
  val tCycle  = io.tCycle

  // Completion latency pipes are per-channel: a CAS in one channel can never
  // be delayed by another channel's completions.
  val backend = Module(new DRAMBackend(p(NastiKey), cfg.backendKey))

  // Two command buses per channel, per JESD235: one row command (ACT / PRE /
  // REF / REFSB) and one column command (CASR / CASW) may issue each cycle.
  val selectedRowCmd = WireInit(cmd_nop)
  val selectedColCmd = WireInit(cmd_nop)

  val pcStateTrackers = Seq.fill(hbmKey.maxPseudoChannels)(Module(new HBMPseudoChannelStateTracker(hbmKey)))

  // Presence bit per (PC, bank): prevents closing a row that still has ready entries
  val bankHasReadyEntries = RegInit(
    VecInit(Seq.fill(hbmKey.maxPseudoChannels * hbmKey.maxBanks)(false.B))
  )

  // isReady / mayPRE depend on this channel's scheduler state at acceptance
  // time, so they are recomputed here (below) rather than taken from the
  // dispatched entry.
  val newReference = Wire(Decoupled(new HBMEntry(p(NastiKey), cfg)))
  newReference.valid    := io.newReference.valid
  newReference.bits     := io.newReference.bits
  io.newReference.ready := newReference.ready

  val rowHitsInPC = VecInit(pcStateTrackers.map { tracker =>
    VecInit(tracker.io.pc.banks.map { _.isRowHit(newReference.bits) }).asUInt
  })

  val refBuffer  = CollapsingBuffer(
    enq               = newReference,
    depth             = cfg.schedulerWindowSize,
    programmableDepth = Some(io.schedulerWindowSize),
  )
  val refList    = refBuffer.io.entries
  val refUpdates = refBuffer.io.updates

  val columnArbiter = Module(new Arbiter(refList.head.bits.cloneType, refList.size))

  // Legality of a command for an entry = bank legal && bank-group legal && PC legal
  def checkLegality(
    getBankField: HBMBankStateTrackerO => Bool,
    getBGField:   HBMPseudoChannelStateTrackerO => Vec[Bool],
    getPCField:   HBMPseudoChannelStateTrackerO => Bool,
  )(entry: HBMEntry): Bool = {
    val bankFields = pcStateTrackers.map { pc => VecInit(pc.io.pc.banks.map(getBankField)).asUInt }
    val bankLegal  = (Mux1H(entry.pcAddrOH, bankFields) & entry.bankAddrOH).orR
    val bgFields   = pcStateTrackers.map { pc => getBGField(pc.io.pc).asUInt }
    val bgLegal    = (Mux1H(entry.pcAddrOH, bgFields) & entry.bankGroupAddrOH).orR
    val pcFields   = VecInit(pcStateTrackers.map { pc => getPCField(pc.io.pc) }).asUInt
    val pcLegal    = (entry.pcAddrOH & pcFields).orR
    bankLegal && bgLegal && pcLegal
  }

  def pcWantsRef(pcAddrOH: UInt): Bool =
    (pcAddrOH & (VecInit(pcStateTrackers.map { _.io.pc.wantREF }).asUInt)).orR

  // A pending REFab must quiesce the entire pseudo-channel. A pending REFSB
  // only needs to quiesce its selected bank; other banks may continue to
  // receive ACT and CAS while that bank is precharged/refreshed. The bank
  // tracker itself enforces tRFCSB after the REFSB command is issued.
  def pendingRefreshBlocks(entry: HBMEntry): Bool = {
    val pending = pcWantsRef(entry.pcAddrOH)
    val selectedBank = Mux1H(entry.pcAddrOH, pcStateTrackers.map(_.io.pc.refsbBank))
    pending && (!io.perBankRefresh || entry.bankAddr === selectedBank)
  }

  val canLegallyCASR = checkLegality(_.canCASR, _.bgCanCASR, _.canCASR) _
  val canLegallyCASW = checkLegality(_.canCASW, _.bgCanCASW, _.canCASW) _
  val canLegallyACT  = checkLegality(_.canACT, _.bgCanACT, _.canACT) _
  // PRE has no bank-group-scope constraint
  val canLegallyPRE  = checkLegality(_.canPRE, o => VecInit(Seq.fill(hbmKey.maxBankGroups)(true.B)), _.canPRE) _

  // ------------------------------------------------------------------
  // Column bus: issue a legal CAS whenever the arbiter offers one
  // ------------------------------------------------------------------
  columnArbiter.io.in <> refList.map({ entry =>
    val candidate = V2D(entry)
    val canCASR   = canLegallyCASR(entry.bits) && backend.io.newRead.ready
    val canCASW   = canLegallyCASW(entry.bits) && backend.io.newWrite.ready
    candidate.valid := entry.valid && entry.bits.isReady &&
      Mux(entry.bits.xaction.isWrite, canCASW, canCASR) &&
      !pendingRefreshBlocks(entry.bits)

    candidate
  })

  val colValid = columnArbiter.io.out.valid
  val colPC    = columnArbiter.io.out.bits.pcAddr
  val colBank  = columnArbiter.io.out.bits.bankAddr
  when(colValid) {
    selectedColCmd := Mux(columnArbiter.io.out.bits.xaction.isWrite, cmd_casw, cmd_casr)
  }
  columnArbiter.io.out.ready := true.B

  val memReqDone = (selectedColCmd === cmd_casr || selectedColCmd === cmd_casw)

  // A row command may not target the bank the column bus is using this cycle
  def conflictsWithCol(pc: UInt, bank: UInt): Bool = colValid && pc === colPC && bank === colBank

  // ------------------------------------------------------------------
  // Row bus: REF/REFSB > refresh-prep PRE > ACT > PRE > yielding REFSB >
  // yielding refresh-prep PRE. An ACT occupies the row bus for two cycles
  // (JESD235 two-frame ACT encoding).
  // ------------------------------------------------------------------
  val rowBusBusy = RegNext(selectedRowCmd === cmd_act, false.B)

  val entryWantsPRE = refList.map { ref =>
    ref.valid && ref.bits.wantPRE() && canLegallyPRE(ref.bits) &&
    !conflictsWithCol(ref.bits.pcAddr, ref.bits.bankAddr)
  }
  val entryWantsACT = refList.map { ref =>
    ref.valid && ref.bits.wantACT() && canLegallyACT(ref.bits) &&
    !pendingRefreshBlocks(ref.bits)
  }

  val preBank    = PriorityMux(entryWantsPRE, refList.map(_.bits.bankAddr))
  val prePC      = PriorityMux(entryWantsPRE, refList.map(_.bits.pcAddr))
  val suggestPre = entryWantsPRE.reduce { _ || _ }

  val actPC   = PriorityMux(entryWantsACT, refList.map(_.bits.pcAddr))
  val actBank = PriorityMux(entryWantsACT, refList.map(_.bits.bankAddr))
  val actRow  = PriorityMux(entryWantsACT, refList.map(_.bits.rowAddr))

  val suggestAct = (entryWantsACT
    .zip(entryWantsPRE))
    .foldRight(false.B)({ case ((act, pre), current) =>
      Mux(act, true.B, !pre && current)
    })

  val rowPC   = WireInit(UInt(hbmKey.pcBits.W), init = prePC)
  val rowBank = WireInit(UInt(hbmKey.bankBits.W), init = preBank)
  val cmdRow  = actRow

  val perBankRefresh    = io.perBankRefresh
  val pcsInUse          = io.pcsInUse
  val pcsWantingRefresh = VecInit(pcStateTrackers.map { _.io.pc.wantREF }).asUInt

  // --- All-bank refresh (REFab) arbitration ---
  val refreshablePCs = VecInit(pcStateTrackers.map { _.io.pc.canREF }).asUInt & pcsInUse
  val refRefPC       = PriorityEncoder(pcsWantingRefresh & refreshablePCs)
  val suggestREF     = !perBankRefresh && (pcsWantingRefresh & refreshablePCs).orR
  // Precharge banks in preparation of an all-bank refresh
  val preRefBanks      = pcStateTrackers.map { pc => PriorityEncoder(pc.io.pc.banks.map { _.canPRE }) }
  val prechargeablePCs = VecInit(pcStateTrackers.map { pc =>
    pc.io.pc.canPRE && (pc.io.pc.banks.map { _.canPRE }.reduce { _ || _ })
  }).asUInt & pcsInUse
  val refPrePC         = PriorityEncoder(pcsWantingRefresh & prechargeablePCs)
  val refPreBank       = PriorityMux(pcsWantingRefresh & prechargeablePCs, preRefBanks)
  val suggestRefPRE    = !perBankRefresh && (pcsWantingRefresh & prechargeablePCs).orR &&
    !conflictsWithCol(refPrePC, refPreBank)

  // --- Per-bank refresh (REFSB) arbitration ---
  // REFSBs that are not overdue (refYields), and the precharges preparing
  // them, rank below demand ACT / PRE.
  val refsbYieldPCs   = VecInit(pcStateTrackers.map(_.io.pc.refYields)).asUInt
  val refsbReadyPCs   = VecInit(pcStateTrackers.map { pc =>
    pc.io.pc.wantREF && pc.io.pc.canREFSB
  }).asUInt & pcsInUse
  // Precharge the refresh-pending bank if it is currently active
  val refsbPrePCs     = VecInit(pcStateTrackers.map { pc =>
    pc.io.pc.wantREF && pc.io.pc.canPRE &&
    Mux1H(UIntToOH(pc.io.pc.refsbBank), pc.io.pc.banks.map(_.canPRE))
  }).asUInt & pcsInUse
  def refsbBankOf(pcs: UInt): UInt = PriorityMux(pcs, pcStateTrackers.map(_.io.pc.refsbBank))

  val refsbNowPCs     = refsbReadyPCs & ~refsbYieldPCs
  val refsbPC         = PriorityEncoder(refsbNowPCs)
  val refsbBankSel    = refsbBankOf(refsbNowPCs)
  val suggestREFSB    = perBankRefresh && refsbNowPCs.orR
  val refsbPreNowPCs  = refsbPrePCs & ~refsbYieldPCs
  val refsbPrePC      = PriorityEncoder(refsbPreNowPCs)
  val refsbPreBank    = refsbBankOf(refsbPreNowPCs)
  val suggestRefsbPRE = perBankRefresh && refsbPreNowPCs.orR &&
    !conflictsWithCol(refsbPrePC, refsbPreBank)

  val refsbLatePCs        = refsbReadyPCs & refsbYieldPCs
  val lateRefsbPC         = PriorityEncoder(refsbLatePCs)
  val lateRefsbBank       = refsbBankOf(refsbLatePCs)
  val suggestLateREFSB    = perBankRefresh && refsbLatePCs.orR
  val refsbPreLatePCs     = refsbPrePCs & refsbYieldPCs
  val lateRefsbPrePC      = PriorityEncoder(refsbPreLatePCs)
  val lateRefsbPreBank    = refsbBankOf(refsbPreLatePCs)
  val suggestLateRefsbPRE = perBankRefresh && refsbPreLatePCs.orR &&
    !conflictsWithCol(lateRefsbPrePC, lateRefsbPreBank)

  when(!rowBusBusy) {
    when(suggestREF) {
      selectedRowCmd := cmd_ref
      rowPC          := refRefPC
    }.elsewhen(suggestREFSB) {
      selectedRowCmd := cmd_refsb
      rowPC          := refsbPC
      rowBank        := refsbBankSel
    }.elsewhen(suggestRefPRE) {
      selectedRowCmd := cmd_pre
      rowPC          := refPrePC
      rowBank        := refPreBank
    }.elsewhen(suggestRefsbPRE) {
      selectedRowCmd := cmd_pre
      rowPC          := refsbPrePC
      rowBank        := refsbPreBank
    }.elsewhen(suggestAct) {
      selectedRowCmd := cmd_act
      rowPC          := actPC
      rowBank        := actBank
    }.elsewhen(suggestPre) {
      selectedRowCmd := cmd_pre
      rowPC          := prePC
      rowBank        := preBank
    }.elsewhen(suggestLateREFSB) {
      selectedRowCmd := cmd_refsb
      rowPC          := lateRefsbPC
      rowBank        := lateRefsbBank
    }.elsewhen(suggestLateRefsbPRE) {
      selectedRowCmd := cmd_pre
      rowPC          := lateRefsbPrePC
      rowBank        := lateRefsbPreBank
    }
  }

  // ------------------------------------------------------------------
  // Scheduler-window bookkeeping
  // ------------------------------------------------------------------
  val entriesStillReady = refUpdates.zip(columnArbiter.io.in).map { case (ref, sel) =>
    when(sel.fire) { ref.valid := false.B }
    !sel.fire && ref.valid && ref.bits.isReady &&
    colBank === ref.bits.bankAddr && colPC === ref.bits.pcAddr
  }

  val otherReadyEntries = entriesStillReady.reduce { _ || _ }
  val casAutoPRE        = Mux(io.openPagePolicy, false.B, memReqDone && !otherReadyEntries)

  refUpdates.foreach({ ref =>
    // Row-bus effects on pending references
    when(rowPC === ref.bits.pcAddr && rowBank === ref.bits.bankAddr) {
      when(selectedRowCmd === cmd_act) {
        ref.bits.isReady := ref.bits.rowAddr === cmdRow
        ref.bits.mayPRE  := false.B
      }.elsewhen(selectedRowCmd === cmd_pre) {
        ref.bits.isReady := false.B
        ref.bits.mayPRE  := false.B
      }
    }
    // Column-bus effects on pending references
    when(colPC === ref.bits.pcAddr && colBank === ref.bits.bankAddr) {
      when(memReqDone && !otherReadyEntries) {
        ref.bits.mayPRE := true.B
      }
    }
  })

  val newRefColBankMatch = newReference.bits.addrMatch(colPC, colBank)
  val newRefRowActMatch  = newReference.bits.addrMatch(rowPC, rowBank, Some(cmdRow))
  val newRefRowBankMatch = newReference.bits.addrMatch(rowPC, rowBank)
  newReference.bits.isReady :=
    selectedRowCmd === cmd_act && newRefRowActMatch ||
      (rowHitsInPC(newReference.bits.pcAddr) & newReference.bits.bankAddrOH).orR &&
      !(memReqDone && casAutoPRE && newRefColBankMatch) &&
      !(selectedRowCmd === cmd_pre && newRefRowBankMatch)

  newReference.bits.mayPRE :=
    Mux(
      io.openPagePolicy,
      newRefColBankMatch && memReqDone && !otherReadyEntries ||
        !bankHasReadyEntries(Cat(newReference.bits.pcAddr, newReference.bits.bankAddr)) &&
        !(selectedRowCmd === cmd_pre && newRefRowBankMatch),
      false.B,
    )

  when(memReqDone) {
    bankHasReadyEntries(Cat(colPC, colBank)) := otherReadyEntries
  }
  when(selectedRowCmd === cmd_act) {
    bankHasReadyEntries(Cat(rowPC, rowBank)) := true.B
  }

  when(newReference.bits.isReady & newReference.fire) {
    bankHasReadyEntries(Cat(newReference.bits.pcAddr, newReference.bits.bankAddr)) := true.B
  }

  val rowPCOH        = UIntToOH(rowPC)
  val colPCOH        = UIntToOH(colPC)
  val rowBankOH      = UIntToOH(rowBank)
  val colBankOH      = UIntToOH(colBank)
  val rowBankGroupOH = UIntToOH(rowBank(hbmKey.bankGroupBits - 1, 0))
  val colBankGroupOH = UIntToOH(colBank(hbmKey.bankGroupBits - 1, 0))

  pcStateTrackers.zipWithIndex.foreach { case (tracker, i) =>
    tracker.io.rowCmd           := selectedRowCmd
    tracker.io.colCmd           := selectedColCmd
    tracker.io.rowCmdUsesThisPC := rowPCOH(i) && selectedRowCmd =/= cmd_nop
    tracker.io.colCmdUsesThisPC := colPCOH(i) && selectedColCmd =/= cmd_nop
    tracker.io.rowBankOH        := rowBankOH
    tracker.io.colBankOH        := colBankOH
    tracker.io.rowBankGroupOH   := rowBankGroupOH
    tracker.io.colBankGroupOH   := colBankGroupOH
    tracker.io.cmdRow           := cmdRow
    tracker.io.autoPRE          := casAutoPRE
    tracker.io.perBankRefresh   := perBankRefresh
    tracker.io.refsbIdleFirst   := io.refsbIdleFirst
    tracker.io.refsbMaxPostponed := io.refsbMaxPostponed
    tracker.io.refsbIdleAge     := io.refsbIdleAge
    tracker.io.refsbYield       := io.refsbYield
    tracker.io.refsbLRU         := io.refsbLRU
    tracker.io.refsbAgeScale    := io.refsbAgeScale
    tracker.io.bankBusy         := refList
      .map { ref => Mux(ref.valid && ref.bits.pcAddrOH(i), ref.bits.bankAddrOH, 0.U) }
      .reduce(_ | _)

    tracker.io.timings := timings
    tracker.io.tCCDR.zip(io.tCCDR).foreach { case (t, r) => t := r }
    tracker.io.tCycle  := tCycle
  }

  backend.io.tCycle        := tCycle
  backend.io.newRead.bits  := ReadResponseMetaData(p(NastiKey), columnArbiter.io.out.bits.xaction)
  backend.io.newRead.valid := memReqDone && !columnArbiter.io.out.bits.xaction.isWrite
  backend.io.readLatency   := timings.tCAS + io.backendLatency

  // Writes are acknowledged immediately once issued
  backend.io.newWrite.bits  := WriteResponseMetaData(p(NastiKey), columnArbiter.io.out.bits.xaction)
  backend.io.newWrite.valid := memReqDone && columnArbiter.io.out.bits.xaction.isWrite
  backend.io.writeLatency   := 1.U

  io.completedWrite <> backend.io.completedWrite
  io.completedRead  <> backend.io.completedRead

  // Dump both command streams (pseudo channel printed in the rank position).
  // Row bus: activate / precharge / refresh / refsb; column bus: read / write.
  // Multi-channel configurations prefix every line with "m<edge base>ch<N>:"
  // so that several bridges sharing one console, and the channels within each,
  // can be separated. A 1-channel model on a striped FASED port uses the
  // stripe index instead. A contiguous one-channel edge leaves the historical
  // format unchanged.
  val monitorPrefix =
    if (hbmKey.maxChannels > 1) FasedEdgeBase().map(b => s"m${b.toString(16)}").getOrElse("") + s"ch$chIdx:"
    else FasedStripePort().map(i => s"ch$i:").getOrElse("")

  val rowCmdMonitor = Module(new CommandBusMonitor(monitorPrefix))
  rowCmdMonitor.io.cmd     := selectedRowCmd
  rowCmdMonitor.io.rank    := rowPC
  rowCmdMonitor.io.bank    := rowBank
  rowCmdMonitor.io.row     := cmdRow
  rowCmdMonitor.io.autoPRE := false.B

  val colCmdMonitor = Module(new CommandBusMonitor(monitorPrefix))
  colCmdMonitor.io.cmd     := selectedColCmd
  colCmdMonitor.io.rank    := colPC
  colCmdMonitor.io.bank    := colBank
  colCmdMonitor.io.row     := 0.U
  colCmdMonitor.io.autoPRE := casAutoPRE
}

// ============================================================================
// The timing model
// ============================================================================

class HBMModelIO(val cfg: HBMModelConfig)(implicit p: Parameters) extends TimingModelIO()(p) {
  val mmReg = new HBMMMRegIO(cfg)
}

class HBMModel(cfg: HBMModelConfig)(implicit p: Parameters)
    extends TimingModel(cfg)(p)
    with HasDRAMMASConstants {

  val longName = "HBM2 Pseudo-Channel First-Ready FCFS MAS"
  def printTimingModelGenerationConfig: Unit = {}

  /** ************************** CHISEL BEGINS ********************************
    */
  lazy val io = IO(new HBMModelIO(cfg))

  val hbmKey    = cfg.hbmKey
  val nChannels = hbmKey.maxChannels

  // Shared front-end: the single AXI4 port bounds request-acceptance
  // bandwidth to one transaction per cycle regardless of channel count.
  val xactionScheduler = Module(new UnifiedFIFOXactionScheduler(p(NastiKey), cfg.transactionQueueDepth, cfg))
  xactionScheduler.io.req          <> nastiReq
  xactionScheduler.io.pendingAWReq := pendingAWReq.value
  xactionScheduler.io.pendingWReq  := pendingWReq.value

  // Decode the next transaction's HBM coordinates once, then dispatch it to
  // the channel selected by the (runtime-programmable) chAddr bits. The
  // isReady / mayPRE flags depend on channel-local scheduler state, so the
  // owning channel recomputes them at acceptance time.
  val newReference = Wire(Decoupled(new HBMEntry(p(NastiKey), cfg)))
  newReference.valid := xactionScheduler.io.nextXaction.valid
  newReference.bits.decode(xactionScheduler.io.nextXaction.bits, io.mmReg)
  newReference.bits.isReady := false.B // recomputed by the owning channel
  newReference.bits.mayPRE  := false.B // recomputed by the owning channel

  // Channel select: constant 0 in single-channel configurations (whose
  // decode and register set are unchanged), programmable otherwise.
  // On a striped FASED port the reported id is the memory-port index, which
  // is constant for this instance (the port already filtered the address).
  val chSel = io.mmReg.chAddr
    .map(_.getSubAddr(xactionScheduler.io.nextXaction.bits.addr))
    .getOrElse(0.U)
  val reportCh = FasedStripePort().map(_.U).getOrElse(chSel)

  val channels = Seq.tabulate(nChannels) { i => Module(new HBMChannelScheduler(cfg, i)) }
  channels.zipWithIndex.foreach { case (channel, i) =>
    channel.io.newReference.valid := newReference.valid && chSel === i.U
    channel.io.newReference.bits  := newReference.bits

    channel.io.timings             := io.mmReg.hbmTimings
    channel.io.tCCDR.zip(io.mmReg.tCCDR).foreach { case (c, r) => c := r }
    channel.io.openPagePolicy      := io.mmReg.openPagePolicy
    channel.io.perBankRefresh      := io.mmReg.perBankRefresh
    channel.io.refsbIdleFirst      := io.mmReg.refsbIdleFirst
    channel.io.refsbMaxPostponed   := io.mmReg.refsbMaxPostponed
    channel.io.refsbIdleAge        := io.mmReg.refsbIdleAge
    channel.io.refsbYield          := io.mmReg.refsbYield
    channel.io.refsbLRU            := io.mmReg.refsbLRU
    channel.io.refsbAgeScale       := io.mmReg.refsbAgeScale
    channel.io.schedulerWindowSize := io.mmReg.schedulerWindowSize
    channel.io.pcsInUse            := io.mmReg.pcAddr.maskToOH()
    channel.io.backendLatency      := io.mmReg.backendLatency
    channel.io.tCycle              := tCycle
  }
  newReference.ready                    := VecInit(channels.map(_.io.newReference.ready))(chSel)
  xactionScheduler.io.nextXaction.ready := newReference.ready

  // --------------------------------------------------------------------
  // Optional request trace (cfg.requestTrace): one CSV line per memory
  // transaction as it is accepted into a channel's scheduler, using the
  // exact same runtime-programmable decode the scheduler itself uses. Format:
  //   HBMREQ,<tCycle>,<isWrite>,<addr hex>,<axi id>,<axi len>,
  //          <channel>,<pseudo channel>,<bank group>,<bank>,<row hex>
  // Purely observational: no state, no effect on newReference readiness.
  // --------------------------------------------------------------------
  if (cfg.requestTrace) {
    when(newReference.fire) {
      printf(
        "HBMREQ,%d,%d,%x,%d,%d,%d,%d,%d,%d,%x\n",
        tCycle,
        newReference.bits.xaction.isWrite,
        xactionScheduler.io.nextXaction.bits.addr,
        newReference.bits.xaction.id,
        newReference.bits.xaction.len,
        reportCh,
        newReference.bits.pcAddr,
        newReference.bits.bankGroupAddr,
        newReference.bits.bankAddr,
        newReference.bits.rowAddr,
      )
    }
  }

  // Merge per-channel completions onto the shared AXI4 response channels.
  // Responses drain at one per cycle (the AXI4 port's bandwidth); channels
  // with simultaneously-completed transactions queue them in their own
  // backend pipes, so one channel's completions never stall another
  // channel's command scheduling.
  val readCompletionArb  = Module(new RRArbiter(new ReadResponseMetaData(p(NastiKey)), nChannels))
  val writeCompletionArb = Module(new RRArbiter(new WriteResponseMetaData(p(NastiKey)), nChannels))
  readCompletionArb.io.in.zip(channels).foreach { case (in, channel) => in <> channel.io.completedRead }
  writeCompletionArb.io.in.zip(channels).foreach { case (in, channel) => in <> channel.io.completedWrite }
  rResp <> readCompletionArb.io.out
  wResp <> writeCompletionArb.io.out
}
