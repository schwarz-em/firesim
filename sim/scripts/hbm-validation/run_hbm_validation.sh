#!/usr/bin/env bash
# End-to-end HBM2 FASED validation:
#   1. builds the AXI4Fuzzer + PointerChaser HBM verilator metasims,
#   2. runs the directed workloads (all-bank refresh, per-bank refresh,
#      closed-page, row-hit-heavy, row-conflict-heavy, PointerChaser),
#   3. extracts the HBM command traces from the metasim logs,
#   4. replays every trace against pinned Ramulator 2.1's HBM2 model as an
#      independent JESD235 timing-legality checker.
#
# Requirements:
#   - the usual FireSim metasim environment (verilator 5.022, sbt, g++, gmp)
#   - RAMULATOR2_DIR: checkout of ramulator2 @ b30320bc938 with its python
#     package importable (pip install -e $RAMULATOR2_DIR) and built bindings
#   - RAMULATOR2_PYTHON (optional): python interpreter with the ramulator
#     package; defaults to $RAMULATOR2_DIR/.venv/bin/python
#
# Usage: from sim/: ./scripts/hbm-validation/run_hbm_validation.sh [outdir]
#                    ./scripts/hbm-validation/run_hbm_validation.sh --check-profiles-only

set -e -o pipefail

check_profiles_only=false
if [[ ${1:-} == --check-profiles-only ]]; then
    check_profiles_only=true
    shift
fi
outdir=$(readlink -f "${1:-hbm-validation-output}")
simdir=$(readlink -f "$(dirname "$0")/../..")
scriptdir="$simdir/scripts/hbm-validation"
cd "$simdir"

fuzzer_gen=generated-src/f2/f2-fasedtests-AXI4Fuzzer-NT10e3_DefaultConfig-HBMF2Config
mk_fased="make TARGET_PROJECT=fasedtests DESIGN=AXI4Fuzzer PLATFORM_CONFIG=HBMF2Config"
mk_pc="make TARGET_PROJECT=midasexamples DESIGN=PointerChaser TARGET_CONFIG=PointerChaserConfig PLATFORM_CONFIG=PointerChaserHBM_DefaultF2Config"

# Checked-in single-model configs are unsuffixed source templates. Expand them
# into +mm_..._0= plusargs and reject any line that was not expanded into the
# exact grammar accepted by the model-0 FASED driver.
conf_args() {
    local profile="custom-runtime-configs/$1"
    local expected_settings=$2
    sed -e '/^[[:space:]]*#/d' -e '/^[[:space:]]*$/d' \
        -e 's/=\(.*\)$/_0=\1/' "$profile" | \
        awk -v profile="$profile" -v expected="$expected_settings" '
            $0 !~ /^\+mm_[[:alnum:]_]+_0=[[:digit:]]+$/ {
                printf "invalid or unexpanded FASED setting in %s:%d: %s\n", \
                    profile, NR, $0 > "/dev/stderr"
                invalid = 1
                next
            }
            {
                setting = $0
                sub(/=.*/, "", setting)
                if (seen[setting]++) {
                    printf "duplicate FASED setting in %s:%d: %s\n", \
                        profile, NR, setting > "/dev/stderr"
                    invalid = 1
                    next
                }
                printf "%s ", $0
            }
            END {
                if (NR != expected) {
                    printf "%s must contain exactly %d settings; found %d\n", \
                        profile, expected, NR > "/dev/stderr"
                    invalid = 1
                }
                exit invalid
            }
        '
}

# Resolve and validate every profile before any build or simulator launch. An
# assignment carries a failed command substitution's status through `set -e`.
hbm_refab_args=$(conf_args hbm2-FRFCFS-2400-OP-REFab.conf 34)
hbm_refsb_args=$(conf_args hbm2-FRFCFS-2400-OP-REFSB.conf 34)
hbm_closedpage_args=$(conf_args hbm2-FRFCFS-2400-CP-REFab.conf 34)
if $check_profiles_only; then
    printf 'Validated expanded model-0 FASED profiles: REF-ab=%s REF-SB=%s closed-page=%s settings\n' \
        "$(wc -w <<< "$hbm_refab_args")" \
        "$(wc -w <<< "$hbm_refsb_args")" \
        "$(wc -w <<< "$hbm_closedpage_args")"
    exit 0
fi

: "${RAMULATOR2_DIR:?set RAMULATOR2_DIR to the pinned ramulator2 checkout}"
PY="${RAMULATOR2_PYTHON:-$RAMULATOR2_DIR/.venv/bin/python}"
mkdir -p "$outdir"

echo "=== [1/4] Building metasims"
$mk_fased TARGET_CONFIG=NT10e3_DefaultConfig verilator
$mk_fased TARGET_CONFIG=FuzzMask3FFF_NT10e3_DefaultConfig verilator
$mk_fased TARGET_CONFIG=FuzzMaskSingleBank_NT10e3_DefaultConfig verilator
$mk_pc verilator

echo "=== [2/4] Running workloads"
$mk_fased TARGET_CONFIG=NT10e3_DefaultConfig \
    COMMON_SIM_ARGS="$hbm_refab_args" \
    LOGFILE="$outdir/t1_fuzzer_refab.log" run-verilator
$mk_fased TARGET_CONFIG=NT10e3_DefaultConfig \
    COMMON_SIM_ARGS="$hbm_refsb_args" \
    LOGFILE="$outdir/t2_fuzzer_refsb.log" run-verilator
$mk_fased TARGET_CONFIG=NT10e3_DefaultConfig \
    COMMON_SIM_ARGS="$hbm_closedpage_args" \
    LOGFILE="$outdir/t3_fuzzer_closedpage.log" run-verilator
$mk_fased TARGET_CONFIG=FuzzMask3FFF_NT10e3_DefaultConfig \
    COMMON_SIM_ARGS="$hbm_refab_args" \
    LOGFILE="$outdir/t4_fuzzer_rowhit.log" run-verilator
$mk_fased TARGET_CONFIG=FuzzMaskSingleBank_NT10e3_DefaultConfig \
    COMMON_SIM_ARGS="$hbm_refab_args" \
    LOGFILE="$outdir/t5_fuzzer_rowconflict.log" run-verilator
# Reproducible PointerChaser list: regenerate mem_init.hex with a fixed seed
pc_gen=generated-src/f2/f2-midasexamples-PointerChaser-PointerChaserConfig-PointerChaserHBM_DefaultF2Config
src/main/resources/midasexamples/generate_memory_init.py --seed 0 --output_file "$pc_gen/mem_init.hex"
$mk_pc COMMON_SIM_ARGS="$hbm_refab_args" \
    LOGFILE="$outdir/t6_pointerchaser.log" run-verilator

echo "=== [3/4] Extracting command traces"
for t in t1_fuzzer_refab t2_fuzzer_refsb t3_fuzzer_closedpage t4_fuzzer_rowhit \
         t5_fuzzer_rowconflict t6_pointerchaser; do
    "$PY" "$scriptdir/parse_fased_trace.py" "$outdir/$t.log" -o "$outdir/$t.csv"
done

echo "=== [4/4] Ramulator timing-legality check"
fails=0
for t in "$outdir"/t*.csv; do
    "$PY" "$scriptdir/check_trace_ramulator.py" "$t" --ramulator "$RAMULATOR2_DIR" \
        | tee -a "$outdir/ramulator_check.log" || fails=$((fails+1))
done

echo
grep -h "PASSED\|FAILED" "$outdir"/t*.log || true
if [ "$fails" -eq 0 ]; then
    echo "HBM validation complete: 0 traces with violations"
else
    echo "HBM validation: $fails trace(s) with violations"; exit 1
fi
