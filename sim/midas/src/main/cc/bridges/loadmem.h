// See LICENSE for license details.

#ifndef __LOADMEM_H
#define __LOADMEM_H

#include <cstdint>
#include <string>
#include <vector>

#include <gmp.h>

#include "core/config.h"
#include "core/widget.h"

class simif_t;

struct LOADMEMWIDGET_struct {
  uint64_t W_ADDRESS_H;
  uint64_t W_ADDRESS_L;
  uint64_t W_LENGTH;
  uint64_t ZERO_OUT_DRAM;
  uint64_t W_DATA;
  uint64_t ZERO_FINISHED;
  uint64_t R_ADDRESS_H;
  uint64_t R_ADDRESS_L;
  uint64_t R_DATA;
};

class loadmem_t final : public widget_t {
public:
  /// The identifier for the bridge type.
  static char KIND;

  loadmem_t(simif_t &simif,
            const LOADMEMWIDGET_struct &mmio_addrs,
            unsigned index,
            const std::vector<std::string> &args,
            const AXI4Config &mem_conf,
            unsigned mem_data_chunk);

  void read_mem(size_t addr, mpz_t &value);
  void write_mem(size_t addr, mpz_t &value);
  void write_mem_chunk(size_t addr, mpz_t &value, size_t bytes);
  // Burst write of raw little-endian 32-bit words (no GMP); bytes is
  // padded with zeros to whole beats.
  void write_mem_chunk_raw(size_t addr, const uint32_t *words, size_t bytes);

  // Helper to zero out all DRAM.
  void zero_out_dram();

  // Loads the contents of memory from a file.
  void load_mem_from_file(const std::string &filename);

  // +loadmem-bin=<file>@<host DRAM offset>: raw binary, written in bursts.
  // Host offset = target address + the memory region's offset (-0x8000_0000
  // for the study images). Returns false if the plusarg is absent.
  bool load_mem_bin_if_requested();

  unsigned get_mem_data_chunk() const { return mem_data_chunk; }

private:
  const LOADMEMWIDGET_struct mmio_addrs;
  const AXI4Config mem_conf;
  const unsigned mem_data_chunk;
  std::string bin_path;
  size_t bin_host_offset = 0;
};

#endif // __LOADMEM_H
