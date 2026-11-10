// See LICENSE for license details.

#include "loadmem.h"

#include <chrono>
#include <cstring>
#include <cinttypes>
#include <cstdio>
#include <fstream>

#include "core/simif.h"

char loadmem_t::KIND;

loadmem_t::loadmem_t(simif_t &simif,
                     const LOADMEMWIDGET_struct &mmio_addrs,
                     unsigned index,
                     const std::vector<std::string> &args,
                     const AXI4Config &mem_conf,
                     unsigned mem_data_chunk)
    : widget_t(simif, &KIND), mmio_addrs(mmio_addrs), mem_conf(mem_conf),
      mem_data_chunk(mem_data_chunk) {
  assert(index == 0 && "only one loadmem widget is allowed");
  for (auto &arg : args) {
    if (arg.find("+loadmem-bin=") == 0) {
      std::string v = arg.substr(13);
      size_t at = v.rfind('@');
      if (at == std::string::npos) {
        fprintf(stderr, "[loadmem] +loadmem-bin needs <file>@<host offset>\n");
        exit(EXIT_FAILURE);
      }
      bin_path = v.substr(0, at);
      bin_host_offset = std::stoull(v.substr(at + 1), nullptr, 0);
    }
  }
}

void loadmem_t::load_mem_from_file(const std::string &filename) {
  fprintf(stdout, "[loadmem] start loading file: %s\n", filename.c_str());
  const auto t0 = std::chrono::steady_clock::now();
  std::ifstream file(filename.c_str());
  if (!file) {
    fprintf(stderr, "[loadmem] cannot open %s\n", filename.c_str());
    exit(EXIT_FAILURE);
  }

  const size_t chunk = mem_conf.data_bits / 4;
  fprintf(stdout, "[loadmem] start loading, chunk = %ld\n", chunk);

  size_t addr = 0;
  std::string line;
  mpz_t data;
  mpz_init(data);
  while (std::getline(file, line)) {
    assert(line.length() % chunk == 0);
    for (int j = line.length() - chunk; j >= 0; j -= chunk) {
      mpz_set_str(data, line.substr(j, chunk).c_str(), 16);
      write_mem(addr, data);
      addr += chunk / 2;
    }
  }
  mpz_clear(data);
  file.close();
  const double secs =
      std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
  fprintf(stdout, "[loadmem] done: %zu B in %.3f s (%.2f MB/s)\n", addr, secs, addr / secs / 1e6);
}

void loadmem_t::read_mem(size_t addr, mpz_t &value) {
  // NB: mpz_t variables may not export <size> <uint32_t> beats, if initialized
  // with an array of zeros.
  simif.write(mmio_addrs.R_ADDRESS_H, addr >> 32);
  simif.write(mmio_addrs.R_ADDRESS_L, addr & ((1ULL << 32) - 1));
  uint32_t data[mem_data_chunk];
  for (size_t i = 0; i < mem_data_chunk; i++) {
    data[i] = simif.read(mmio_addrs.R_DATA);
  }
  mpz_import(value, mem_data_chunk, -1, sizeof(uint32_t), 0, 0, data);
}

void loadmem_t::write_mem(size_t addr, mpz_t &value) {
  simif.write(mmio_addrs.W_ADDRESS_H, addr >> 32);
  simif.write(mmio_addrs.W_ADDRESS_L, addr & ((1ULL << 32) - 1));
  simif.write(mmio_addrs.W_LENGTH, 1);
  size_t size;
  uint32_t *data =
      (uint32_t *)mpz_export(nullptr, &size, -1, sizeof(uint32_t), 0, 0, value);
  for (size_t i = 0; i < mem_data_chunk; i++) {
    simif.write(mmio_addrs.W_DATA, i < size ? data[i] : 0);
  }
}

static size_t ceil_div(size_t a, size_t b) { return ((a)-1) / (b) + 1; }

void loadmem_t::write_mem_chunk(size_t addr, mpz_t &value, size_t bytes) {
  const unsigned mem_data_chunk_bytes = mem_data_chunk * sizeof(uint32_t);

  simif.write(mmio_addrs.W_ADDRESS_H, addr >> 32);
  simif.write(mmio_addrs.W_ADDRESS_L, addr & ((1ULL << 32) - 1));

  size_t num_beats = ceil_div(bytes, mem_data_chunk_bytes);
  simif.write(mmio_addrs.W_LENGTH, num_beats);
  size_t size;
  uint32_t *data =
      (uint32_t *)mpz_export(nullptr, &size, -1, sizeof(uint32_t), 0, 0, value);
  for (size_t i = 0; i < num_beats * mem_data_chunk; i++) {
    simif.write(mmio_addrs.W_DATA, i < size ? data[i] : 0);
  }
}

void loadmem_t::zero_out_dram() {
  simif.write(mmio_addrs.ZERO_OUT_DRAM, 1);
  while (!simif.read(mmio_addrs.ZERO_FINISHED))
    ;
}

void loadmem_t::write_mem_chunk_raw(size_t addr, const uint32_t *words, size_t bytes) {
  const size_t beat_bytes = mem_data_chunk * sizeof(uint32_t);
  const size_t num_beats = ceil_div(bytes, beat_bytes);
  const size_t nwords_in = (bytes + sizeof(uint32_t) - 1) / sizeof(uint32_t);
  simif.write(mmio_addrs.W_ADDRESS_H, addr >> 32);
  simif.write(mmio_addrs.W_ADDRESS_L, addr & ((1ULL << 32) - 1));
  simif.write(mmio_addrs.W_LENGTH, num_beats);
  for (size_t i = 0; i < num_beats * mem_data_chunk; i++) {
    simif.write(mmio_addrs.W_DATA, i < nwords_in ? words[i] : 0);
  }
}

bool loadmem_t::load_mem_bin_if_requested() {
  if (bin_path.empty())
    return false;
  FILE *f = fopen(bin_path.c_str(), "rb");
  if (!f) {
    fprintf(stderr, "[loadmem] cannot open %s\n", bin_path.c_str());
    exit(EXIT_FAILURE);
  }
  fprintf(stdout, "[loadmem] start loading binary %s at host offset 0x%zx\n",
          bin_path.c_str(), bin_host_offset);
  fflush(stdout);
  const auto t0 = std::chrono::steady_clock::now();
  // 4 KiB bursts, as the TSI loadmem bypass uses (and LoadMem's page split).
  const size_t burst = 4096;
  std::vector<uint32_t> buf(burst / sizeof(uint32_t));
  size_t total = 0, n;
  while ((n = fread(buf.data(), 1, burst, f)) > 0) {
    if (n % sizeof(uint32_t))
      std::memset(reinterpret_cast<char *>(buf.data()) + n, 0, sizeof(uint32_t) - n % sizeof(uint32_t));
    write_mem_chunk_raw(bin_host_offset + total, buf.data(), n);
    total += n;
  }
  fclose(f);
  const double secs =
      std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
  fprintf(stdout, "[loadmem] binary done: %zu B in %.3f s (%.2f MB/s)\n", total, secs,
          total / secs / 1e6);
  fflush(stdout);
  return true;
}
