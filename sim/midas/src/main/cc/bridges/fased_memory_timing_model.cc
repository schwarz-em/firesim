// See LICENSE for license details.

#include <algorithm>
#include <charconv>
#include <exception>
#include <iostream>
#include <stdio.h>

#include "fased_memory_timing_model.h"

void Histogram::init() {
  // Read out the initial values
  write(enable, 1);
  for (size_t i = 0; i < HISTOGRAM_SIZE; i++) {
    write(addr, i);
    latency[i] = read64(dataH, dataL, BIN_H_MASK);
  }
  // Disable readout enable; otherwise histogram updates will be gated
  write(enable, 0);
}

void Histogram::finish() {
  // Read out the initial values
  write(enable, 1);
  for (size_t i = 0; i < HISTOGRAM_SIZE; i++) {
    write(addr, i);
    latency[i] = read64(dataH, dataL, BIN_H_MASK) - latency[i];
  }
  // Disable readout enable; otherwise histogram updates will be gated
  write(enable, 0);
}

void AddrRangeCounter::init() {
  nranges = read("numRanges");
  range_bytes = new uint64_t[nranges];

  write(enable, 1);
  for (size_t i = 0; i < nranges; i++) {
    write(addr, i);
    range_bytes[i] = read64(dataH, dataL, RANGE_H_MASK);
  }
  write(enable, 0);
}

void AddrRangeCounter::finish() {
  write(enable, 1);
  for (size_t i = 0; i < nranges; i++) {
    write(addr, i);
    range_bytes[i] = read64(dataH, dataL, RANGE_H_MASK);
  }
  write(enable, 0);
}

char FASEDMemoryTimingModel::KIND;

FASEDMemoryTimingModel::FASEDMemoryTimingModel(
    simif_t &simif,
    AddressMap &&addr_map,
    unsigned modelno,
    const std::vector<std::string> &args,
    const std::string &stats_file_name,
    size_t mem_size)
    : bridge_driver_t(simif, &KIND), addr_map(std::move(addr_map)),
      output_files(stats_file_name, modelno), modelno(modelno),
      mem_size(mem_size) {

  const std::string prefix = "+mm_";
  const std::string suffix = "_" + std::to_string(modelno);
  const std::string hardware_defaults_key = "useHardwareDefaultRuntimeSettings";
  for (const auto &arg : args) {
    if (arg.rfind(prefix, 0) != 0) {
      continue;
    }

    const std::string sub_arg = arg.substr(prefix.size());
    const size_t delimiter_idx = sub_arg.find('=');
    const std::string key_and_model = sub_arg.substr(0, delimiter_idx);
    if (key_and_model.size() < suffix.size() ||
        key_and_model.compare(
            key_and_model.size() - suffix.size(), suffix.size(), suffix) != 0) {
      continue;
    }
    const std::string key =
        key_and_model.substr(0, key_and_model.size() - suffix.size());
    if (key.empty()) {
      throw std::runtime_error("[FASED] empty register name for model " +
                               std::to_string(modelno));
    }

    // This is the only nullary plusarg supported by fased
    if (delimiter_idx == std::string::npos) {
      if (key != hardware_defaults_key) {
        throw std::runtime_error("[FASED] unknown nullary plusarg for model " +
                                 std::to_string(modelno) + ": " + key);
      }
      if (useHardwareDefaults) {
        throw std::runtime_error(
            "[FASED] duplicate hardware-default opt-in for model " +
            std::to_string(modelno));
      }
      useHardwareDefaults = true;
      continue;
    }

    // All other plusargs are key-value pairs that will be written to the
    // bridge module
    if (key == hardware_defaults_key) {
      throw std::runtime_error(
          "[FASED] hardware-default opt-in must not have a value for model " +
          std::to_string(modelno));
    }
    if (!addr_map.w_reg_exists(key)) {
      throw std::runtime_error("[FASED] unknown register: " + key);
    }
    const std::string value_text = sub_arg.substr(delimiter_idx + 1);
    uint32_t value = 0;
    const auto parse_result = std::from_chars(
        value_text.data(), value_text.data() + value_text.size(), value, 10);
    if (value_text.empty() || parse_result.ec != std::errc() ||
        parse_result.ptr != value_text.data() + value_text.size()) {
      throw std::runtime_error("[FASED] invalid uint32 value for model " +
                               std::to_string(modelno) + " register " + key +
                               ": " + value_text);
    }
    const bool inserted = user_configuration.emplace(key, value).second;
    if (!inserted) {
      throw std::runtime_error("[FASED] duplicate configuration register for "
                               "model " +
                               std::to_string(modelno) + ": " + key);
    }
  }

  stats_file.open(output_files.stats, std::ofstream::out);
  if (!stats_file.is_open()) {
    throw std::runtime_error("Could not open output file: " +
                             output_files.stats);
  }

  for (const auto &pair : addr_map.r_registers) {
    // Only profile readable registers
    if (!addr_map.w_reg_exists((pair.first))) {
      // Iterate through substrings to exclude
      bool exclude = false;
      for (auto &substr : profile_exclusion) {
        if (pair.first.find(substr) != std::string::npos) {
          exclude = true;
        }
      }
      if (!exclude) {
        profile_reg_addrs.push_back(pair.second);
        stats_file << pair.first << ",";
      }
    }
  }
  stats_file << std::endl;

  if (addr_map.w_reg_exists("hostReadLatencyHist_enable")) {
    histograms.emplace_back(&simif, addr_map, "hostReadLatency");
    histograms.emplace_back(&simif, addr_map, "hostWriteLatency");
    histograms.emplace_back(&simif, addr_map, "targetReadLatency");
    histograms.emplace_back(&simif, addr_map, "targetWriteLatency");
    histograms.emplace_back(&simif, addr_map, "ingressReadLatency");
    histograms.emplace_back(&simif, addr_map, "ingressWriteLatency");
    histograms.emplace_back(&simif, addr_map, "totalReadLatency");
    histograms.emplace_back(&simif, addr_map, "totalWriteLatency");
  }

  if (addr_map.w_reg_exists("readRanges_enable")) {
    rangectrs.emplace_back(&simif, addr_map, "read");
    rangectrs.emplace_back(&simif, addr_map, "write");
  }
}

void FASEDMemoryTimingModel::profile() {
  for (auto addr : profile_reg_addrs) {
    stats_file << read(addr) << ",";
  }
  stats_file << std::endl;
}

void FASEDMemoryTimingModel::init() {
  const auto is_configuration_excluded = [this](const std::string &key) {
    return std::any_of(configuration_exclusion.begin(),
                       configuration_exclusion.end(),
                       [&key](const std::string &substring) {
                         return key.find(substring) != std::string::npos;
                       });
  };
  const bool has_runtime_settings =
      std::any_of(addr_map.w_registers.begin(),
                  addr_map.w_registers.end(),
                  [&is_configuration_excluded](const auto &entry) {
                    return !is_configuration_excluded(entry.first);
                  });
  if (has_runtime_settings && user_configuration.empty() &&
      !useHardwareDefaults) {
    throw std::runtime_error(
        "[FASED] no runtime configuration provided for model " +
        std::to_string(modelno) +
        "; provide every setting or explicitly opt in to hardware defaults");
  }

  // Validate the entire selected configuration before the first MMIO. This
  // prevents a malformed partial profile from changing an early register and
  // then failing on a later one.
  for (const auto &[key, addr] : addr_map.w_registers) {
    (void)addr;
    if (user_configuration.find(key) != user_configuration.end() ||
        is_configuration_excluded(key)) {
      continue;
    }
    if (!useHardwareDefaults) {
      throw std::runtime_error(
          "[FASED] no value provided for configuration register in model " +
          std::to_string(modelno) + ": " + key);
    }
    if (!addr_map.r_reg_exists(key)) {
      throw std::runtime_error(
          "[FASED] cannot use hardware default for unreadable configuration "
          "register in model " +
          std::to_string(modelno) + ": " + key);
    }
  }

  for (auto &[key, addr] : addr_map.w_registers) {
    // If the user provided a configuration option, use it and
    // overwrite whatever the hardware register was initialized to.
    auto user_it = user_configuration.find(key);
    if (user_it != user_configuration.end()) {
      write(addr, user_it->second);
      continue;
    }

    // If the registers is excluded, emit a warning only when some, but not
    // all, registers have been specified through arguments.
    if (is_configuration_excluded(key)) {
      if (!user_configuration.empty()) {
        fprintf(
            stderr, "[FASED] Ignoring writeable register: %s\n", key.c_str());
      }
      continue;
    }

    // Preflight above established both the explicit opt-in and readability.
    auto init_val = simif.read(addr_map.r_addr(key));
    fprintf(stderr,
            "[FASED] Using hardware default of %u for configuration "
            "register %s\n",
            init_val,
            key.c_str());
  }

  if (has_runtime_settings) {
    fprintf(stderr,
            "[FASED] Model %u accepted %zu explicit runtime settings; "
            "hardware defaults %s\n",
            modelno,
            user_configuration.size(),
            useHardwareDefaults ? "enabled" : "disabled");
  }

  for (auto &hist : histograms) {
    hist.init();
  }
  for (auto &rctr : rangectrs) {
    rctr.init();
  }
}

void FASEDMemoryTimingModel::finish() {
  for (auto &hist : histograms) {
    hist.finish();
  }
  for (auto &rctr : rangectrs) {
    rctr.finish();
  }

  if (has_latency_histograms()) {
    std::ofstream histogram_file;
    histogram_file.open(output_files.latency_histogram, std::ofstream::out);
    if (!histogram_file.is_open()) {
      throw std::runtime_error("Could not open histogram output file: " +
                               output_files.latency_histogram);
    }

    // Header
    for (auto &hist : histograms) {
      histogram_file << hist.name << ",";
    }
    histogram_file << std::endl;
    // Data
    for (size_t i = 0; i < HISTOGRAM_SIZE; i++) {
      for (auto &hist : histograms) {
        histogram_file << hist.latency[i] << ",";
      }
      histogram_file << std::endl;
    }
    histogram_file.close();
  }

  if (!rangectrs.empty()) {
    size_t nranges = rangectrs[0].nranges;
    std::ofstream rangectr_file;

    rangectr_file.open(output_files.range_counters, std::ofstream::out);
    if (!rangectr_file.is_open()) {
      throw std::runtime_error("Could not open range counter file: " +
                               output_files.range_counters);
    }

    rangectr_file << "Address,";
    for (auto &rctr : rangectrs) {
      rangectr_file << rctr.name << ",";
    }
    rangectr_file << std::endl;

    for (size_t i = 0; i < nranges; i++) {
      rangectr_file << std::hex << (i * mem_size / nranges) << ",";
      for (auto &rctr : rangectrs) {
        rangectr_file << std::dec << rctr.range_bytes[i] << ",";
      }
      rangectr_file << std::endl;
    }
    rangectr_file.close();
  }

  // Always retain the terminal counter values.  Periodic profiling is
  // optional, and the simulation may also finish before its first interval.
  profile();
  stats_file.close();
}
