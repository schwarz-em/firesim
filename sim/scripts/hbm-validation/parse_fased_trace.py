#!/usr/bin/env python3
"""Parse an HBM command trace out of a FASED metasim log.

The FASED HBM model instantiates two CommandBusMonitors (row bus and column
bus). In a verilator metasim their printfs land in the simulator log
(stderr), one line per issued command, stamped with the model cycle:

    activate(  pc, bank,  row); //  cycle     (row bus)
    precharge( pc, bank, preAll); // cycle     (row bus)
    refresh(   pc); // cycle                   (row bus, REFab)
    refsb(     pc, bank); // cycle             (row bus, REFSB)
    read(      pc, bank, col, autoPRE, burstChop); // cycle          (column bus)
    write(     pc, bank, col, autoPRE, burstChop, mask, data); // cycle (column bus)

In multi-channel configurations every line is prefixed with `ch<N>:` (each
channel has its own pair of command buses / monitors); unprefixed lines are
reported as channel 0. Multi-channel models also tag the prefix with their
AXI edge base, `m<hex base>ch<N>:`; when several such bridges share one log,
--model-base selects one of them.

`nop(n);` filler lines are ignored. The `rank` position of the DDR3-style
monitor holds the HBM pseudo-channel.

Output: CSV with one command per line:
    cycle,cmd,channel,pc,bankgroup,bank,bank_in_group,row,autopre

Bank-group decode matches HBMEntry: the group lives in the LOW bankGroupBits
of the bank address (bg = bank & (groups-1); bank_in_group = bank >> groupBits).
"""

import argparse
import csv
import re
import sys

# Chisel %d pads with spaces; %x prints hex.
NUM = r"\s*(\d+)"
HEX = r"\s*([0-9a-fA-F]+)"
CHAN_RE = re.compile(r"(?:m([0-9a-f]+))?ch(\d+):")
MODEL_RE = re.compile(r"\[FASED\] Model (\d+) accepted ")
PATTERNS = [
    ("ACT", re.compile(rf"activate\({NUM},{NUM},{NUM}\); //{NUM}")),
    ("PRE", re.compile(rf"precharge\({NUM},{NUM},{NUM}\); //{NUM}")),
    ("REFab", re.compile(rf"refresh\({NUM}\); //{NUM}")),
    ("REFpb", re.compile(rf"refsb\({NUM},{NUM}\); //{NUM}")),
    ("RD", re.compile(rf"read\({NUM},{NUM},{NUM},{HEX},{HEX}\); //{NUM}")),
    ("WR", re.compile(rf"write\({NUM},{NUM},{NUM},{HEX},{HEX},{NUM},{NUM}\); //{NUM}")),
]


def parse_lines(lines, bank_groups=4, model_base=None):
    """Yield dicts, one per HBM command found in the log (of one bridge if model_base is set)."""
    group_bits = bank_groups.bit_length() - 1
    for line in lines:
        chan_m = CHAN_RE.search(line)
        if model_base is not None and (not chan_m or chan_m.group(1) != model_base):
            continue
        channel = int(chan_m.group(2)) if chan_m else 0
        for cmd, pat in PATTERNS:
            m = pat.search(line)
            if not m:
                continue
            g = m.groups()
            if cmd == "ACT":
                pc, bank, row, cycle = map(int, g)
                autopre = 0
            elif cmd == "PRE":
                pc, bank, _pre_all, cycle = map(int, g)
                row, autopre = -1, 0
            elif cmd == "REFab":
                pc, cycle = int(g[0]), int(g[1])
                bank, row, autopre = -1, -1, 0
            elif cmd == "REFpb":
                pc, bank, cycle = map(int, g)
                row, autopre = -1, 0
            elif cmd == "RD":
                pc, bank, _col, autopre, _bc, cycle = (
                    int(g[0]), int(g[1]), int(g[2]), int(g[3], 16), int(g[4], 16), int(g[5]))
                row = -1
            else:  # WR
                pc, bank, _col, autopre, _bc, _mask, _data, cycle = (
                    int(g[0]), int(g[1]), int(g[2]), int(g[3], 16), int(g[4], 16),
                    int(g[5]), int(g[6]), int(g[7]))
                row = -1
            yield {
                "cycle": cycle,
                "cmd": cmd,
                "channel": channel,
                "pc": pc,
                "bankgroup": -1 if bank < 0 else bank & (bank_groups - 1),
                "bank": bank,
                "bank_in_group": -1 if bank < 0 else bank >> group_bits,
                "row": row,
                "autopre": autopre,
            }
            break


def require_demultiplexed(lines, model_base=None):
    """Reject traces that cannot distinguish independent FASED bridges."""
    model_ids = {int(m.group(1)) for line in lines if (m := MODEL_RE.search(line))}
    bases = {m.group(1) for line in lines if (m := CHAN_RE.search(line)) and m.group(1)}
    if model_base is not None:
        if model_base not in bases:
            raise ValueError(f"no commands tagged m{model_base}; tagged bridges: {sorted(bases)}")
        return
    if len(bases) > 1:
        raise ValueError(f"commands from bridges {sorted(bases)}; select one with --model-base")
    if len(model_ids) <= 1:
        return
    for line in lines:
        if not CHAN_RE.search(line) and any(pat.search(line) for _, pat in PATTERNS):
            raise ValueError(
                f"{len(model_ids)} FASED models emitted untagged commands; "
                "the trace cannot be demultiplexed for timing replay"
            )


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("logfile", help="metasim log containing CommandBusMonitor output")
    ap.add_argument("-o", "--output", default="-", help="output CSV (default stdout)")
    ap.add_argument("--bank-groups", type=int, default=4)
    ap.add_argument("--model-base", help="hex AXI edge base of the bridge to extract (from the m<base> tag)")
    args = ap.parse_args()
    model_base = args.model_base.lower().removeprefix("0x") if args.model_base else None

    with open(args.logfile, errors="replace") as f:
        lines = f.readlines()
    require_demultiplexed(lines, model_base)
    cmds = sorted(parse_lines(lines, args.bank_groups, model_base), key=lambda c: c["cycle"])

    out = sys.stdout if args.output == "-" else open(args.output, "w", newline="")
    w = csv.DictWriter(
        out, fieldnames=["cycle", "cmd", "channel", "pc", "bankgroup", "bank", "bank_in_group", "row", "autopre"])
    w.writeheader()
    for c in cmds:
        w.writerow(c)
    if out is not sys.stdout:
        out.close()
        counts = {}
        for c in cmds:
            counts[c["cmd"]] = counts.get(c["cmd"], 0) + 1
        print(f"{args.output}: {len(cmds)} commands {counts}")


if __name__ == "__main__":
    main()
