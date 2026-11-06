# See LICENSE for license details.

####################################
# Verilator MIDAS-Level Simulators #
####################################

VERILATOR_CXXOPTS ?= -O2
VERILATOR_MAKEFLAGS ?= -j8 VM_PARALLEL_BUILDS=1

verilator = $(GENERATED_DIR)/V$(DESIGN)
verilator_debug = $(GENERATED_DIR)/V$(DESIGN)-debug

$(verilator) $(verilator_debug): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(VERILATOR_CXXOPTS) -D RTLSIM
# Do not add $ORIGIN here: this value crosses make, a nested make, the shell,
# and Verilator's generated makefile.  The dollar is otherwise consumed into
# `RIGIN` or an empty RPATH component (which searches the current directory).
# Driver dependencies supply their explicit host-library RPATHs.
$(verilator) $(verilator_debug): export LDFLAGS := $(LDFLAGS) $(common_ld_flags)

verilator_driver_deps := $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(simulator_verilog)

define make_verilator
        $(MAKE) -C $(simif_dir) $(VERILATOR_MAKEFLAGS) $(1) \
                PLATFORM=$(PLATFORM) \
                DRIVER_NAME=$(DESIGN) \
                GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
                GEN_DIR=$(GENERATED_DIR) \
                DRIVER="$(DRIVER_CC)" \
		VERILATOR_FLAGS="$(EXTRA_VERILATOR_FLAGS)"
endef

$(verilator): $(verilator_driver_deps)
	$(call make_verilator, verilator)

$(verilator_debug): $(verilator_driver_deps)
	$(call make_verilator, verilator-debug)

.PHONY: verilator verilator-debug
verilator: $(verilator)
verilator-debug: $(verilator_debug)
