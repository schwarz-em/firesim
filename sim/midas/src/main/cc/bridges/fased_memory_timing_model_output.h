// See LICENSE for license details.

#ifndef __FASED_MEMORY_TIMING_MODEL_OUTPUT_H
#define __FASED_MEMORY_TIMING_MODEL_OUTPUT_H

#include <string>
#include <utility>

// Output files written by one FASED memory-timing-model bridge.  A simulated
// system may contain multiple instances, so every bridge-owned filename must
// include the model number supplied by Golden Gate.
struct FASEDMemoryTimingModelOutputFiles {
  FASEDMemoryTimingModelOutputFiles(std::string stats_file_name,
                                    unsigned modelno)
      : stats(std::move(stats_file_name)),
        latency_histogram("latency_histogram" + std::to_string(modelno) +
                          ".csv"),
        range_counters("range_counters" + std::to_string(modelno) + ".csv") {}

  const std::string stats;
  const std::string latency_histogram;
  const std::string range_counters;
};

#endif // __FASED_MEMORY_TIMING_MODEL_OUTPUT_H
