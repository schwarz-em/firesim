#!/usr/bin/env python3
"""Move fpnew/cvfpu packages above the modules that import them.

Golden Gate appends Radiance FP blackboxes at the end of FireSim-generated.sv.
Those files define `package fpnew_pkg`, `cf_math_pkg`, and `defs_div_sqrt_mvp`
after modules that import them. Verilator compiles the file in order and
rejects the imports. This rewrites the generated SV in place. It is a no-op
when those packages are absent (MemPerf images).
"""
import sys
from pathlib import Path

NAMES = ("fpnew_pkg", "cf_math_pkg", "defs_div_sqrt_mvp")


def main() -> None:
    path = Path(sys.argv[1])
    lines = path.read_text().splitlines(keepends=True)
    pkgs = []
    stripped = []
    i = 0
    while i < len(lines):
        s = lines[i].lstrip()
        if (s.startswith("package ") and s.rstrip().endswith(";")
                and any(n in s for n in NAMES)):
            block = [lines[i]]
            i += 1
            while i < len(lines) and not lines[i].lstrip().startswith("endpackage"):
                block.append(lines[i])
                i += 1
            if i < len(lines):
                block.append(lines[i])
                i += 1
            pkgs.append("".join(block))
            continue
        stripped.append(lines[i])
        i += 1
    if not pkgs:
        return
    imp = next(i for i, line in enumerate(stripped)
               if any(f"import {n}" in line for n in NAMES))
    path.write_text("".join(stripped[:imp] + pkgs + ["\n"] + stripped[imp:]))
    print(f"reordered {len(pkgs)} fp packages in {path}")


if __name__ == "__main__":
    main()
