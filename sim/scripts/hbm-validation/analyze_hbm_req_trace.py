#!/usr/bin/env python3
"""Per-workload statistics for an HBMREQ request trace CSV.

Input: the CSV produced by parse_hbm_req_trace.py from a metasim log that ran
the Radiance MemPerf traffic-generator tile (FireSimRadianceMemPerfConfig) or
any other HBMREQ-tracing run.

The MemPerf tile runs its traffic patterns back-to-back: all 16 lanes finish a
pattern, synchronize, and start the next one. Between patterns the memory
system drains, so the HBM request stream shows an idle gap. This script
segments the request stream on idle gaps (default: >= 2000 target cycles with
no accepted request) and reports, per segment and overall:

  requests, reads/writes, first/last cycle, distinct addresses,
  pseudo-channel / bank-group / bank / row distributions, and a
  request-level row-transition ("row switch") count per (pc,bg,bank).

Row switches are a proxy for potential row misses at the scheduler input; the
authoritative row hit/miss behaviour is in the model's DRAM command trace
(activate/read/write lines), which the Ramulator legality checker consumes.
"""

import argparse
import csv
import sys
from collections import Counter, defaultdict


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("csvfile", help="CSV from parse_hbm_req_trace.py")
    ap.add_argument("--gap", type=int, default=2000,
                    help="idle-cycle threshold that starts a new segment [2000]")
    ap.add_argument("--names", default=None,
                    help="optional comma-separated workload names, in order")
    args = ap.parse_args()

    rows = []
    with open(args.csvfile) as f:
        for r in csv.DictReader(f):
            rows.append({
                "cycle": int(r["cycle"]),
                "rw": r["rw"],
                "addr": int(r["addr"], 16),
                "ch": int(r.get("channel", 0)),
                "pc": int(r["pc"]),
                "bg": int(r["bank_group"]),
                "bank": int(r["bank"]),
                "row": int(r["row"]),
            })
    if not rows:
        print("no requests found", file=sys.stderr)
        sys.exit(1)

    names = args.names.split(",") if args.names else []

    # segment on idle gaps
    segments = [[rows[0]]]
    for prev, cur in zip(rows, rows[1:]):
        if cur["cycle"] - prev["cycle"] >= args.gap:
            segments.append([])
        segments[-1].append(cur)

    def describe(tag, seg):
        reads = sum(1 for r in seg if r["rw"] == "R")
        writes = len(seg) - reads
        chans = Counter(r["ch"] for r in seg)
        pcs = Counter(r["pc"] for r in seg)
        bgs = Counter(r["bg"] for r in seg)
        banks = Counter(r["bank"] for r in seg)
        rowc = Counter(r["row"] for r in seg)
        uniq = len({r["addr"] for r in seg})
        # request-level row switches per (ch,pc,bg,bank)
        last_row = {}
        switches = 0
        for r in seg:
            key = (r["ch"], r["pc"], r["bg"], r["bank"])
            if key in last_row and last_row[key] != r["row"]:
                switches += 1
            last_row[key] = r["row"]
        span = seg[-1]["cycle"] - seg[0]["cycle"]
        print(f"{tag}: {len(seg)} reqs (R={reads} W={writes}) "
              f"cycles {seg[0]['cycle']}..{seg[-1]['cycle']} (span {span}) "
              f"uniq_addrs={uniq}")
        print(f"    ch={dict(sorted(chans.items()))}")
        print(f"    pc={dict(sorted(pcs.items()))}")
        print(f"    bg={dict(sorted(bgs.items()))}")
        print(f"    bank={dict(sorted(banks.items()))}")
        print(f"    rows={len(rowc)} distinct, row_switches={switches}")
        for ch in sorted(chans):
            sub = [r for r in seg if r["ch"] == ch]
            print(f"    path{ch}: n={len(sub)} "
                  f"R={sum(1 for r in sub if r['rw']=='R')} "
                  f"W={sum(1 for r in sub if r['rw']=='W')} "
                  f"pc={sorted({r['pc'] for r in sub})} "
                  f"bg={sorted({r['bg'] for r in sub})} "
                  f"banks={sorted({r['bank'] for r in sub})}")

    print(f"=== {len(rows)} requests, {len(segments)} segments "
          f"(gap >= {args.gap} cycles) ===")
    for i, seg in enumerate(segments):
        name = names[i] if i < len(names) else f"segment{i}"
        describe(f"[{i:3d}] {name}", seg)
    print("=== overall ===")
    describe("all", rows)


if __name__ == "__main__":
    main()
