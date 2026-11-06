#!/usr/bin/env python3
"""Check the JEDEC per-bank refresh ordering rules on a FASED HBM command trace.

Ramulator's device model (check_trace_ramulator.py) checks command timing and
bank state but not these set-level rules (JESD270-4A 6.3.2.5):

  - REFpb may address the banks of a pseudo channel in any order, but a bank
    may not be refreshed again until every bank of its set has been;
  - no REFpb may follow the last REFpb of a set within tRFCpb;
  - at most 9 x tREFI may pass between two refreshes of the same bank;
  - at most 9 REFpb may go to one bank within tREFI;
  - at most eight REFab's worth (8 x banks REFpb) may be postponed or pulled
    in: the REFpb issued by cycle t stay within 8 x banks of t / tREFIpb,
    tREFIpb = tREFI / banks, counting from cycle 0 (model reset).

Refreshes of the first and last partial sets are checked for repeats only;
the interval rule applies to the whole trace. The postponement rule is also
checked at the last cycle of the trace.

Usage: check_refpb_rules.py trace.csv --trefi 3900 --trfcpb 160 [--banks 16]
"""

import argparse
import csv
import sys
from collections import defaultdict


def check(rows, trefi, trfcpb, banks):
    seq = defaultdict(list)
    end = 0
    for r in rows:
        end = max(end, int(r["cycle"]))
        if r["cmd"] == "REFpb":
            seq[(int(r["channel"]), int(r["pc"]))].append((int(r["cycle"]), int(r["bank"])))
    max_debt = 8 * banks
    violations = []

    def check_debt(key, cycle, issued):
        owed = cycle * banks // trefi
        if owed - issued > max_debt:
            violations.append(f"{key} cycle {cycle}: {owed - issued} REFpb postponed (> {max_debt})")
        elif issued - owed > max_debt:
            violations.append(f"{key} cycle {cycle}: {issued - owed} REFpb pulled in (> {max_debt})")

    for key, refs in sorted(seq.items()):
        refs.sort()
        in_set = set()
        set_end = None
        last = {}
        per_bank = defaultdict(list)
        for issued, (cycle, bank) in enumerate(refs):
            check_debt(key, cycle, issued)
            if set_end is not None and cycle - set_end < trfcpb:
                violations.append(f"{key} cycle {cycle}: REFpb {cycle - set_end} cycles after a set ended (tRFCpb {trfcpb})")
            set_end = None
            if bank in in_set:
                violations.append(f"{key} cycle {cycle}: bank {bank} refreshed twice in one set")
            in_set.add(bank)
            if bank in last and cycle - last[bank] > 9 * trefi:
                violations.append(f"{key} cycle {cycle}: bank {bank} unrefreshed for {cycle - last[bank]} cycles (> 9 x tREFI)")
            last[bank] = cycle
            history = per_bank[bank]
            history.append(cycle)
            if len(history) > 9 and cycle - history[-10] < trefi:
                violations.append(f"{key} cycle {cycle}: bank {bank} refreshed 10 times within {cycle - history[-10]} cycles (< tREFI)")
            if len(in_set) == banks:
                in_set = set()
                set_end = cycle
        check_debt(key, end, len(refs))
    return seq, violations


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("trace", help="CSV from parse_fased_trace.py")
    ap.add_argument("--trefi", type=int, required=True)
    ap.add_argument("--trfcpb", type=int, required=True)
    ap.add_argument("--banks", type=int, default=16)
    args = ap.parse_args()
    with open(args.trace) as f:
        seq, violations = check(list(csv.DictReader(f)), args.trefi, args.trfcpb, args.banks)
    total = sum(len(v) for v in seq.values())
    for v in violations[:20]:
        print(v)
    print(f"{args.trace}: {total} REFpb over {len(seq)} pseudo channels, {len(violations)} rule violations")
    sys.exit(1 if violations else 0)


if __name__ == "__main__":
    main()
