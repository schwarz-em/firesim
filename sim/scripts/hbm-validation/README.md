# HBM2 FASED model validation

Tools to validate the FASED HBM2 timing model (`midas.models.HBMModel`)
against [Ramulator 2.1](https://github.com/CMU-SAFARI/ramulator2) as an
independent JESD235 timing reference.

Pinned Ramulator commit: `b30320bc9385b708e86b67ebb9f48858cc66d798`.

## Methodology

1. Verilator metasims of directed workloads are run against the HBM model
   (`HBMF2Config` / `PointerChaserHBM`). The model's two `CommandBusMonitor`s
   print every issued HBM command (ACT / PRE / RD / WR / REFab / REFSB with
   pseudo-channel, bank, row, cycle) into the metasim log.
2. `parse_fased_trace.py` extracts a normalized command trace (CSV).
3. `check_trace_ramulator.py` replays the trace, cycle-stamped, into
   Ramulator's HBM2 device model (`HBM2_2400Mbps` / `HBM2_8Gb`) via its
   nanobind test harness. Every command is first probed; a command Ramulator
   reports as not-ready (state machine or timing) at the cycle FASED issued
   it counts as a violation.

This is a **timing-legality** check, not a cycle-identical-scheduling
comparison: FASED and Ramulator's own controller schedule differently, but a
clean run proves FASED never issues a command earlier than JESD235 permits.
In addition, the model's own hardware assertions (bank / bank-group /
pseudo-channel trackers) are active in every metasim.

## Workloads / traces

| Trace | Workload | What it stresses |
| ----- | -------- | ---------------- |
| t1_fuzzer_refab | AXI4Fuzzer, 1000 xactions, full 22b addresses | bank-group + PC interleave, RD/WR turnaround, REFab |
| t2_fuzzer_refsb | same, `perBankRefresh=1` | REFSB scheduling, tRFCSB / tRREFD |
| t3_fuzzer_closedpage | same, `openPagePolicy=0` | RDA/WRA auto-precharge timing |
| t4_fuzzer_rowhit | fuzzer masked to 0x3FFF (PC0, row 0, all banks) | row hits, tCCDS vs tCCDL |
| t5_fuzzer_rowconflict | fuzzer masked to 0x3F83FF (PC0 bank 0, 128 rows) | row conflicts, tRC / tRAS / tRP / tRCD |
| t6_pointerchaser | PointerChaser (dependent loads, seeded list) | serial latency path end-to-end |

## Running

```bash
# one-time: build ramulator2 at the pinned commit with python bindings,
# and pip install -e it into a venv (see repo README)
export RAMULATOR2_DIR=/path/to/ramulator2
cd sim && ./scripts/hbm-validation/run_hbm_validation.sh /tmp/hbm-validation
```

Individual traces can be checked with:

```bash
./scripts/hbm-validation/parse_fased_trace.py metasim.log -o trace.csv
./scripts/hbm-validation/check_trace_ramulator.py trace.csv --ramulator $RAMULATOR2_DIR
```

## Per-request trace (single-channel controller validation)

Building against `PLATFORM_CONFIG=HBMF2ReqTraceConfig` (fasedtests) — or any
platform config layered with `firesim.configs.WithHBMRequestTrace` — makes the
HBM model print one line per memory transaction accepted by its scheduler:

```
HBMREQ,<tCycle>,<isWrite>,<addr hex>,<axi id>,<axi len>,<channel>,<pc>,<bg>,<bank>,<row hex>
```

The channel/pc/bg/bank/row fields are the model's own runtime-programmable
decode of the address, i.e. exactly what the scheduler uses (traces produced
before multi-channel support lack the `<channel>` field; the parser accepts
both formats and reports channel 0). The monitor is elaboration-gated
(`HBMModelConfig.requestTrace`), adds no state, and exerts no backpressure.
Convert a metasim log into a clean CSV (with the derived column and byte
offset) using:

```bash
./scripts/hbm-validation/parse_hbm_req_trace.py metasim.log -o reqs.csv
```

This request stream is the input side of the PARE-controller-vs-HBM-model
comparison: replay it into the controller under test, then compare decode,
command-trace legality (via `check_trace_ramulator.py`), and aggregate
row-hit / latency statistics.

Per-workload/segment statistics (PC / bank-group / bank / row distributions,
read-write mix, row switches) can be computed from that CSV with:

```bash
./scripts/hbm-validation/analyze_hbm_req_trace.py reqs.csv --gap 2000
```

The `--gap` heuristic segments the request stream on idle gaps, which for the
Radiance MemPerf traffic-generator target correspond to the lane-barrier
between traffic patterns.

## Multi-channel HBM

`firesim.configs.WithHBMChannels(n)` (alias `WithHBMQuadChannel` for n = 4)
makes one `HBMModel` instance model n fully independent HBM channels: each
channel owns its reference window, FR-FCFS row/column scheduling, PC / bank
group / bank timing trackers, refresh state, command buses, and completion
pipes (`HBMChannelScheduler` in `HBMModel.scala`). The channels share only
the AXI4 front-end transaction queue and the response arbiters. The channel
select is runtime-programmable (`+mm_chAddr_offset` / `+mm_chAddr_mask`,
only present in multi-channel builds); the default is a contiguous partition
with the channel index directly above one channel's capacity.

fasedtests platform configs: `HBMF2QuadChConfig` /
`HBMF2QuadChReqTraceConfig`. Directed target config `FuzzChannelBoundaries`
fuzzes exactly the first and last 64B line of every 1 MiB channel partition
of the 22-bit fuzzer space (channel bits [21:20]).

Multi-channel command traces prefix every monitor line with `ch<N>:`;
`parse_fased_trace.py` turns that into a `channel` CSV column, and
`check_trace_ramulator.py` replays each channel into its own Ramulator
device instance (HBM channels share no timing state, so per-channel
legality is the correct check). Request-to-channel routing in an HBMREQ
trace is validated against the programmed partition with:

```bash
./scripts/hbm-validation/check_channel_routing.py reqs.csv --offset 20 --mask 3
```

Runtime configs `hbm2-FRFCFS-2400-{OP-REFab,OP-REFSB}-4ch-fuzzer.conf`
partition the fuzzer space as [5:0] offset | [9:6] col | [13:10] bank |
[14] PC | [19:15] row (channel-local) | [21:20] channel;
`hbm2-FRFCFS-2400-OP-REFab-4ch-radiance.conf` instead puts the channel bits
at [19:18] (256 KiB partitions) so the Radiance MemPerf 1 MiB footprint
exercises all four channels, keeping the row decode identical to the
single-channel config.

## Radiance -> HBM integrated run (chipyard graphics)

With this FireSim branch checked out as `sims/firesim` inside a
`ucb-bar/chipyard:graphics` workspace (see `HBM_RADIANCE_INTEGRATION_REPORT.md`
at the chipyard root), the full single-channel Radiance-traffic-into-HBM
metasim is built and run with:

```bash
cd <chipyard>/sims/firesim/sim
make TARGET_PROJECT_MAKEFRAG=<chipyard>/generators/firechip/chip/src/main/makefrag/firesim \
     TARGET_CONFIG=FireSimRadianceMemPerfConfig \
     PLATFORM_CONFIG_PACKAGE=firesim.configs \
     PLATFORM_CONFIG=WithHBMRequestTrace_HBM2FRFCFS16GBDualPC_BaseF2Config \
     verilator
cd generated-src/f2/f2-*FireSimRadianceMemPerfConfig*/
./VFireSim +permissive $(grep -v '^\s*$\|^#' \
    <firesim>/sim/custom-runtime-configs/hbm2-FRFCFS-2400-OP-REFab.conf | \
    sed 's/=\(.*\)$/_0=\1/' | tr '\n' ' ') \
    +fesvr-step-size=128 +max-cycles=12000000 +permissive-off none > run.log 2>&1
```

The log then contains both the `HBMREQ` request trace and the DRAM command
trace, which feed `parse_hbm_req_trace.py` / `parse_fased_trace.py` /
`check_trace_ramulator.py` unchanged. `examples/` holds a representative
parsed request-trace sample and the per-segment statistics from a 24M-cycle
run (613,255 requests; 1,396,609 commands; 0 Ramulator violations).

## HBM runtime configurations

`sim/custom-runtime-configs/hbm2-FRFCFS-2400-{OP-REFab,OP-REFSB,CP-REFab}.conf`
hold the complete HBM2-2400 register set (every field of
`HBMProgrammableTimings`, the address decode sub-fields, and the policy
bits). These files are deliberately unsuffixed source templates: append the
FASED model index (for example, transform `=17` into `_0=17`) before launch.
Passing a raw template now fails closed instead of silently selecting hardware
defaults. `run_hbm_validation.sh` performs this expansion and validates every
expanded line before it starts a build or simulator. Run
`./scripts/hbm-validation/run_hbm_validation.sh --check-profiles-only` from
`sim/` to exercise that preflight without building or launching a simulator.
Supplying a complete explicit configuration also regression-tests the
`HasProgrammableRegisters` traversal: the FASED driver throws at startup if
any writable register is left uncovered by the supplied `+mm_*` arguments.
