#!/usr/bin/env python3
"""Convert `HBMREQ,...` lines from a FASED HBM metasim log into a clean CSV.

The HBM model (built with WithHBMRequestTrace / HBMF2ReqTraceConfig) prints
one line per memory transaction accepted by its scheduler:

    HBMREQ,<tCycle>,<isWrite>,<addr hex>,<axi id>,<axi len>,<channel>,<pc>,<bg>,<bank>,<row hex>

The older single-channel format without the <channel> field is also accepted
(channel is reported as 0).

channel / pc / bg / bank / row are the model's own runtime-programmable
decode of the address (the same values the scheduler uses), so they are
authoritative for the mapping the run was actually configured with. This
script additionally derives the column (line-within-row) and byte offset from
the address, which the hardware does not decode explicitly, using the same
mask/offset scheme as the model's +mm_bankAddr/pcAddr/rowAddr plusargs.

Defaults correspond to the model's default layout for the fasedtests fuzzer:
  [5:0] byte offset | [9:6] column | [13:10] bank | [14] PC | [21:15] row
"""

import argparse
import csv
import re
import sys

# New format (with channel): 10 data fields; old format: 9 data fields.
LINE_RE = re.compile(
    r"^HBMREQ,\s*(\d+),(\d),\s*([0-9a-fA-F]+),\s*(\d+),\s*(\d+),"
    r"(?:\s*(\d+),)?"
    r"\s*(\d+),\s*(\d+),\s*(\d+),\s*([0-9a-fA-F]+)\s*$"
)

FIELDS = [
    "cycle", "rw", "addr", "axi_id", "axi_len",
    "channel", "pc", "bank_group", "bank", "row", "col", "byte_offset",
]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("logfile", help="metasim log containing HBMREQ lines")
    ap.add_argument("-o", "--output", default=None, help="output CSV (default stdout)")
    ap.add_argument("--line-bits", type=int, default=6,
                    help="log2(bytes per column access / line) [6]")
    ap.add_argument("--col-offset", type=int, default=6,
                    help="bit offset of the column field [6]")
    ap.add_argument("--col-mask", type=lambda x: int(x, 0), default=0xF,
                    help="mask of the column field after shifting [0xf]")
    args = ap.parse_args()

    rows = []
    with open(args.logfile) as f:
        for line in f:
            m = LINE_RE.match(line.strip())
            if not m:
                continue
            cycle, isw, addr, axi_id, axi_len, ch, pc, bg, bank, row = m.groups()
            addr_i = int(addr, 16)
            rows.append({
                "cycle":       int(cycle),
                "rw":          "W" if isw == "1" else "R",
                "addr":        f"0x{addr_i:x}",
                "axi_id":      int(axi_id),
                "axi_len":     int(axi_len),
                "channel":     int(ch) if ch is not None else 0,
                "pc":          int(pc),
                "bank_group":  int(bg),
                "bank":        int(bank),
                "row":         int(row, 16),
                "col":         (addr_i >> args.col_offset) & args.col_mask,
                "byte_offset": addr_i & ((1 << args.line_bits) - 1),
            })

    out = open(args.output, "w", newline="") if args.output else sys.stdout
    w = csv.DictWriter(out, fieldnames=FIELDS)
    w.writeheader()
    w.writerows(rows)
    if args.output:
        out.close()
        print(f"wrote {len(rows)} requests to {args.output}")


if __name__ == "__main__":
    main()
