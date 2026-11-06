#!/usr/bin/env python3
"""Validate request-to-channel routing in a multi-channel HBMREQ trace.

Input: the CSV produced by parse_hbm_req_trace.py. For every request this
checks that the channel the model reported (the `channel` column, i.e. the
channel whose scheduler actually accepted the request) equals the channel
implied by the programmed partition: (addr >> offset) & mask. It also
reports per-channel read/write totals and, for boundary auditing, the
requests whose line address is the first or last line of a channel region.

Exit status is nonzero on any routing mismatch.

Usage:
  check_channel_routing.py reqs.csv --offset 20 --mask 3 [--line-bytes 64]
"""

import argparse
import csv
import sys
from collections import defaultdict


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("csvfile", help="CSV from parse_hbm_req_trace.py")
    ap.add_argument("--offset", type=int, required=True,
                    help="programmed +mm_chAddr_offset")
    ap.add_argument("--mask", type=lambda x: int(x, 0), required=True,
                    help="programmed +mm_chAddr_mask")
    ap.add_argument("--line-bytes", type=int, default=64,
                    help="line size used for boundary reporting [64]")
    args = ap.parse_args()

    region = 1 << args.offset
    mismatches = 0
    counts = defaultdict(lambda: [0, 0])  # channel -> [reads, writes]
    boundary_hits = defaultdict(int)      # (channel, "first"|"last") -> count
    lo = None
    hi = None

    with open(args.csvfile) as f:
        for r in csv.DictReader(f):
            addr = int(r["addr"], 16)
            got = int(r["channel"])
            want = (addr >> args.offset) & args.mask
            if got != want:
                mismatches += 1
                print(f"MISMATCH cycle={r['cycle']} addr={r['addr']}: "
                      f"model routed ch{got}, partition says ch{want}")
            counts[got][0 if r["rw"] == "R" else 1] += 1
            off_in_region = addr & (region - 1)
            if off_in_region < args.line_bytes:
                boundary_hits[(got, "first")] += 1
            if off_in_region >= region - args.line_bytes:
                boundary_hits[(got, "last")] += 1
            lo = addr if lo is None else min(lo, addr)
            hi = addr if hi is None else max(hi, addr)

    total = sum(rd + wr for rd, wr in counts.values())
    if region >= (1 << 20):
        size = f"{region // (1 << 20)} MiB"
    elif region >= (1 << 10):
        size = f"{region // (1 << 10)} KiB"
    else:
        size = f"{region} B"
    print(f"\n{args.csvfile}: {total} requests, partition = "
          f"addr[{args.offset + args.mask.bit_length() - 1}:{args.offset}] "
          f"({size} per channel), {mismatches} mismatches")
    for ch in sorted(counts):
        rd, wr = counts[ch]
        print(f"  channel {ch}: {rd + wr} requests (R={rd} W={wr})")
    if boundary_hits:
        print("  boundary lines touched (first/last line of a region):")
        for (ch, side) in sorted(boundary_hits):
            print(f"    ch{ch} {side}-line requests: {boundary_hits[(ch, side)]}")
    print(f"  lowest addr: 0x{lo:x}   highest addr: 0x{hi:x}")
    sys.exit(1 if mismatches else 0)


if __name__ == "__main__":
    main()
