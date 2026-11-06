// See LICENSE for license details.

#include "bridges/fased_memory_timing_model_output.h"

#include <cassert>
#include <set>
#include <string>

int main() {
  std::set<std::string> stats_files;
  std::set<std::string> histogram_files;
  std::set<std::string> range_counter_files;

  for (unsigned modelno = 0; modelno < 5; ++modelno) {
    const std::string stats_file =
        "memory_stats" + std::to_string(modelno) + ".csv";
    const FASEDMemoryTimingModelOutputFiles files(stats_file, modelno);

    assert(files.stats == stats_file);
    assert(files.latency_histogram ==
           "latency_histogram" + std::to_string(modelno) + ".csv");
    assert(files.range_counters ==
           "range_counters" + std::to_string(modelno) + ".csv");

    stats_files.insert(files.stats);
    histogram_files.insert(files.latency_histogram);
    range_counter_files.insert(files.range_counters);
  }

  assert(stats_files.size() == 5);
  assert(histogram_files.size() == 5);
  assert(range_counter_files.size() == 5);
  return 0;
}
