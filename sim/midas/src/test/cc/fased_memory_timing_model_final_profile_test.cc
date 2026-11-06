// See LICENSE for license details.

#include "bridges/fased_memory_timing_model.h"
#include "core/config.h"

#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <fstream>
#include <iterator>
#include <map>
#include <optional>
#include <string>
#include <unistd.h>
#include <vector>

// Minimal definitions needed to exercise the production bridge driver without
// linking a host-platform implementation.
simif_t::simif_t(const TargetConfig &config) : config(config) {}
simif_t::~simif_t() = default;
CPUManagedStreamIO &simif_t::get_cpu_managed_stream_io() { std::abort(); }
FPGAManagedStreamIO &simif_t::get_fpga_managed_stream_io() { std::abort(); }
std::string_view simif_t::get_target_name() const { return config.target_name; }
int simif_t::run(simulation_t &) { return 0; }

widget_t::widget_t(simif_t &simif, const void *kind)
    : simif(simif), kind(kind) {}
widget_t::~widget_t() = default;

void bridge_driver_t::write(size_t addr, uint32_t data) {
  simif.write(addr, data);
}
uint32_t bridge_driver_t::read(size_t addr) { return simif.read(addr); }

class TestSimif final : public simif_t {
public:
  explicit TestSimif(const TargetConfig &config) : simif_t(config) {}

  void write(size_t addr, uint32_t data) override { values[addr] = data; }
  uint32_t read(size_t addr) override { return values.at(addr); }

  std::map<size_t, uint32_t> values;
};

static AddressMap make_instrumented_address_map() {
  std::vector<std::pair<std::string, uint32_t>> reads = {
      {"counterA", 0x204},
      {"numRanges", 0x200},
  };
  std::vector<std::pair<std::string, uint32_t>> writes;

  for (const std::string name : {"hostReadLatency",
                                 "hostWriteLatency",
                                 "targetReadLatency",
                                 "targetWriteLatency",
                                 "ingressReadLatency",
                                 "ingressWriteLatency",
                                 "totalReadLatency",
                                 "totalWriteLatency"}) {
    reads.emplace_back(name + "Hist_dataH", 0x100);
    reads.emplace_back(name + "Hist_dataL", 0x104);
    writes.emplace_back(name + "Hist_enable", 0x108);
    writes.emplace_back(name + "Hist_addr", 0x10c);
  }

  for (const std::string name : {"read", "write"}) {
    reads.emplace_back(name + "Ranges_dataH", 0x110);
    reads.emplace_back(name + "Ranges_dataL", 0x114);
    writes.emplace_back(name + "Ranges_enable", 0x118);
    writes.emplace_back(name + "Ranges_addr", 0x11c);
  }

  return AddressMap(reads, writes);
}

int main() {
  const TargetConfig config = {
      {1, 32, 32},
      {1, 32, 64},
      1,
      std::nullopt,
      std::nullopt,
      {0, 0},
      "fased-final-profile-test",
  };
  TestSimif simif(config);
  simif.values.emplace(0x100, 0);
  simif.values.emplace(0x104, 0);
  simif.values.emplace(0x110, 0);
  simif.values.emplace(0x114, 0);
  simif.values.emplace(0x200, 2);
  simif.values.emplace(0x204, 99);
  simif.values.emplace(0x10, 11);
  simif.values.emplace(0x14, 22);

  char stats_path[] = "/tmp/fased-final-profile-XXXXXX";
  const int fd = mkstemp(stats_path);
  if (fd < 0) {
    return 1;
  }
  close(fd);

  char output_dir[] = "/tmp/fased-final-output-XXXXXX";
  if (mkdtemp(output_dir) == nullptr) {
    std::remove(stats_path);
    return 1;
  }
  const int original_cwd = open(".", O_RDONLY);
  if (original_cwd < 0 || chdir(output_dir) != 0) {
    if (original_cwd >= 0) {
      close(original_cwd);
    }
    std::remove(stats_path);
    rmdir(output_dir);
    return 1;
  }

  {
    AddressMap address_map({{"counterA", 0x10}, {"counterB", 0x14}}, {});
    FASEDMemoryTimingModel model(
        simif, std::move(address_map), 3, {}, stats_path, 1 << 20);
    model.init();
    model.finish();
  }

  std::ifstream stats_file(stats_path);
  const std::string contents((std::istreambuf_iterator<char>(stats_file)),
                             std::istreambuf_iterator<char>());
  const bool emitted_disabled_instrumentation =
      access("latency_histogram3.csv", F_OK) == 0 ||
      access("range_counters3.csv", F_OK) == 0;

  {
    auto address_map = make_instrumented_address_map();
    FASEDMemoryTimingModel model(
        simif, std::move(address_map), 4, {}, "memory_stats4.csv", 1 << 20);
    model.init();
    model.finish();
  }
  const bool emitted_indexed_instrumentation =
      access("latency_histogram4.csv", F_OK) == 0 &&
      access("range_counters4.csv", F_OK) == 0;
  const bool emitted_legacy_instrumentation =
      access("latency_histogram.csv", F_OK) == 0 ||
      access("range_counters.csv", F_OK) == 0;

  std::remove("memory_stats4.csv");
  std::remove("latency_histogram4.csv");
  std::remove("range_counters4.csv");

  fchdir(original_cwd);
  close(original_cwd);
  std::remove(stats_path);
  rmdir(output_dir);

  // No periodic profile() call was made: finish() must still append one row.
  return contents == "counterA,counterB,\n11,22,\n" &&
                 !emitted_disabled_instrumentation &&
                 emitted_indexed_instrumentation &&
                 !emitted_legacy_instrumentation
             ? 0
             : 1;
}
