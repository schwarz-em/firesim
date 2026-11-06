// See LICENSE for license details.

#include "bridges/fased_memory_timing_model.h"
#include "core/config.h"

#include <cstdio>
#include <cstdlib>
#include <map>
#include <optional>
#include <stdexcept>
#include <string>
#include <unistd.h>
#include <utility>
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
  explicit TestSimif(const TargetConfig &config) : simif_t(config) {
    values.emplace(0x10, 14);
    values.emplace(0x14, 5);
  }

  void write(size_t addr, uint32_t data) override {
    writes.emplace_back(addr, data);
    values[addr] = data;
  }

  uint32_t read(size_t addr) override {
    reads.push_back(addr);
    return values.at(addr);
  }

  std::map<size_t, uint32_t> values;
  std::vector<std::pair<size_t, uint32_t>> writes;
  std::vector<size_t> reads;
};

class TempStatsFile final {
public:
  TempStatsFile() {
    char path_template[] = "/tmp/fased-config-test-XXXXXX";
    const int fd = mkstemp(path_template);
    if (fd < 0) {
      throw std::runtime_error("mkstemp failed");
    }
    close(fd);
    path = path_template;
  }

  ~TempStatsFile() { std::remove(path.c_str()); }

  TempStatsFile(const TempStatsFile &) = delete;
  TempStatsFile &operator=(const TempStatsFile &) = delete;

  std::string path;
};

static const TargetConfig config = {
    {1, 32, 32},
    {1, 32, 64},
    1,
    std::nullopt,
    std::nullopt,
    {0, 0},
    "fased-config-test",
};

static AddressMap make_address_map() {
  const std::vector<std::pair<std::string, uint32_t>> registers = {
      {"dramTimings_tCAS", 0x10},
      {"dramTimings_tRRD", 0x14},
  };
  return AddressMap(registers, registers);
}

static AddressMap make_partially_readable_address_map() {
  const std::vector<std::pair<std::string, uint32_t>> reads = {
      {"dramTimings_tCAS", 0x10},
  };
  const std::vector<std::pair<std::string, uint32_t>> writes = {
      {"dramTimings_tCAS", 0x10},
      {"dramTimings_tRRD", 0x14},
  };
  return AddressMap(reads, writes);
}

static bool expect_failure_with_map(const std::vector<std::string> &args,
                                    unsigned modelno,
                                    const std::string &expected_message,
                                    AddressMap &&address_map) {
  TestSimif simif(config);
  TempStatsFile stats_file;
  try {
    FASEDMemoryTimingModel model(
        simif, std::move(address_map), modelno, args, stats_file.path, 1 << 20);
    model.init();
  } catch (const std::runtime_error &error) {
    const bool expected_error =
        std::string(error.what()).find(expected_message) != std::string::npos;
    const bool no_mmio = simif.writes.empty() && simif.reads.empty();
    if (!no_mmio) {
      std::fprintf(stderr,
                   "failure performed %zu write(s) and %zu read(s)\n",
                   simif.writes.size(),
                   simif.reads.size());
    }
    return expected_error && no_mmio;
  }
  return false;
}

static bool expect_failure(const std::vector<std::string> &args,
                           unsigned modelno,
                           const std::string &expected_message) {
  return expect_failure_with_map(
      args, modelno, expected_message, make_address_map());
}

static bool
expect_success(const std::vector<std::string> &args,
               unsigned modelno,
               const std::vector<std::pair<size_t, uint32_t>> &expected_writes,
               const std::vector<size_t> &expected_reads) {
  TestSimif simif(config);
  TempStatsFile stats_file;
  try {
    FASEDMemoryTimingModel model(
        simif, make_address_map(), modelno, args, stats_file.path, 1 << 20);
    model.init();
  } catch (const std::exception &error) {
    std::fprintf(stderr, "unexpected FASED error: %s\n", error.what());
    return false;
  }
  return simif.writes == expected_writes && simif.reads == expected_reads;
}

static bool check(bool condition, const char *description) {
  if (!condition) {
    std::fprintf(stderr, "FAILED: %s\n", description);
  }
  return condition;
}

int main() {
  bool passed = true;

  passed &= check(expect_failure({}, 0, "no runtime configuration"),
                  "an empty configuration fails closed");

  passed &= check(
      expect_success(
          {"+mm_useHardwareDefaultRuntimeSettings_0"}, 0, {}, {0x10, 0x14}),
      "explicit hardware-default opt-in permits an empty configuration");

  passed &= check(
      expect_failure({"+mm_dramTimings_tCAS_0=14"}, 0, "dramTimings_tRRD"),
      "a partial configuration fails before any MMIO");

  passed &= check(expect_success({"+mm_dramTimings_tCAS_0=14",
                                  "+mm_useHardwareDefaultRuntimeSettings_0"},
                                 0,
                                 {{0x10, 14}},
                                 {0x14}),
                  "hardware-default opt-in may fill a partial configuration");

  passed &= check(
      expect_failure({"+mm_dramTimings_tCAS_0=14", "+mm_dramTimings_tRRD_0=5"},
                     1,
                     "no runtime configuration"),
      "settings for another model do not satisfy this model");

  passed &= check(expect_failure({"+mm_dramTimings_tCAS_10=14",
                                  "+mm_dramTimings_tRRD_10=5"},
                                 1,
                                 "no runtime configuration"),
                  "model 10 settings do not alias model 1");

  passed &= check(
      expect_failure({"+mm_dramTimings_tCAS=14", "+mm_dramTimings_tRRD=5"},
                     0,
                     "no runtime configuration"),
      "raw unsuffixed profile templates must be expanded");

  passed &= check(expect_failure({"+mm__0=1"}, 0, "empty register name"),
                  "an empty current-model register name is rejected");

  passed &=
      check(expect_failure(
                {"+mm_notARegister_0=1"}, 0, "unknown register: notARegister"),
            "an unknown current-model register is rejected");

  passed &= check(
      expect_failure({"+mm_notARegister_0"}, 0, "unknown nullary plusarg"),
      "an unknown current-model nullary plusarg is rejected");

  passed &=
      check(expect_failure({"+mm_useHardwareDefaultRuntimeSettings_0=1"},
                           0,
                           "hardware-default opt-in must not have a value"),
            "a valued hardware-default opt-in is rejected");

  passed &= check(
      expect_failure({"+mm_dramTimings_tCAS_0=", "+mm_dramTimings_tRRD_0=5"},
                     0,
                     "invalid uint32 value"),
      "an empty value is rejected");

  passed &= check(
      expect_failure({"+mm_dramTimings_tCAS_0=-1", "+mm_dramTimings_tRRD_0=5"},
                     0,
                     "invalid uint32 value"),
      "a negative value is rejected");

  passed &= check(expect_failure({"+mm_dramTimings_tCAS_0=5junk",
                                  "+mm_dramTimings_tRRD_0=5"},
                                 0,
                                 "invalid uint32 value"),
                  "trailing value junk is rejected");

  passed &= check(
      expect_failure({"+mm_dramTimings_tCAS_0=5=6", "+mm_dramTimings_tRRD_0=5"},
                     0,
                     "invalid uint32 value"),
      "an extra value delimiter is rejected");

  passed &= check(expect_failure({"+mm_dramTimings_tCAS_0=4294967296",
                                  "+mm_dramTimings_tRRD_0=5"},
                                 0,
                                 "invalid uint32 value"),
                  "a value above UINT32_MAX is rejected");

  passed &= check(expect_failure({"+mm_dramTimings_tCAS_0=14",
                                  "+mm_dramTimings_tRRD_0=5",
                                  "+mm_dramTimings_tRRD_0=6"},
                                 0,
                                 "duplicate configuration register"),
                  "duplicate setting keys are rejected");

  passed &= check(expect_failure({"+mm_useHardwareDefaultRuntimeSettings_0",
                                  "+mm_useHardwareDefaultRuntimeSettings_0"},
                                 0,
                                 "duplicate hardware-default opt-in"),
                  "duplicate hardware-default opt-ins are rejected");

  passed &= check(
      expect_success({"+mm_dramTimings_tCAS_0=14", "+mm_dramTimings_tRRD_0=5"},
                     0,
                     {{0x10, 14}, {0x14, 5}},
                     {}),
      "a complete configuration is accepted");

  passed &=
      check(expect_success({"+mm_dramTimings_tCAS_0=2147483648",
                            "+mm_dramTimings_tRRD_0=4294967295"},
                           0,
                           {{0x10, UINT32_C(2147483648)}, {0x14, UINT32_MAX}},
                           {}),
            "the full uint32 range, including INT_MAX + 1 and "
            "UINT32_MAX, is accepted");

  passed &=
      check(expect_failure_with_map({"+mm_dramTimings_tCAS_0=14",
                                     "+mm_useHardwareDefaultRuntimeSettings_0"},
                                    0,
                                    "unreadable configuration register",
                                    make_partially_readable_address_map()),
            "hardware-default preflight rejects an unreadable register before "
            "MMIO");

  return passed ? 0 : 1;
}
