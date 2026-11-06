#!/usr/bin/env bash
# Compile and run the standalone FASED bridge-driver tests without generating
# RTL or building a simulator.

set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
sim_dir=$(cd "$script_dir/../../../.." && pwd)
test_output_dir=$(mktemp -d)
trap 'rm -rf -- "$test_output_dir"' EXIT

cd "$sim_dir"

compiler=${CXX:-g++}
common_flags=(-std=c++20 -Wall -Wextra -Werror -Imidas/src/main/cc)
driver_sources=(
    midas/src/main/cc/bridges/fased_memory_timing_model.cc
    midas/src/main/cc/core/address_map.cc
)

run_test() {
    local mode=$1
    local name=$2
    shift 2
    local mode_flags=()
    if [[ "$mode" == release ]]; then
        mode_flags=(-DNDEBUG)
    fi
    "$compiler" "${common_flags[@]}" "${mode_flags[@]}" "$@" \
        -o "$test_output_dir/${name}-${mode}"
    if [[ "$name" == fased-memory-timing-model-config ]]; then
        local diagnostic_log="$test_output_dir/${name}-${mode}.stderr"
        if ! "$test_output_dir/${name}-${mode}" 2> "$diagnostic_log"; then
            cat "$diagnostic_log" >&2
            return 1
        fi
        grep -Fqx \
            '[FASED] Model 0 accepted 0 explicit runtime settings; hardware defaults enabled' \
            "$diagnostic_log"
        grep -Fqx \
            '[FASED] Model 0 accepted 1 explicit runtime settings; hardware defaults enabled' \
            "$diagnostic_log"
        grep -Fqx \
            '[FASED] Model 0 accepted 2 explicit runtime settings; hardware defaults disabled' \
            "$diagnostic_log"
    else
        "$test_output_dir/${name}-${mode}"
    fi
}

for mode in debug release; do
    run_test "$mode" fased-memory-timing-model-output-files \
        midas/src/test/cc/fased_memory_timing_model_output_files_test.cc
    run_test "$mode" fased-memory-timing-model-final-profile \
        midas/src/test/cc/fased_memory_timing_model_final_profile_test.cc \
        "${driver_sources[@]}"
    run_test "$mode" fased-memory-timing-model-config \
        midas/src/test/cc/fased_memory_timing_model_config_test.cc \
        "${driver_sources[@]}"
done

printf 'FASED bridge-driver tests passed in debug and release modes\n'
