RISCV_PREFIX ?= riscv64-unknown-elf-
CC := $(RISCV_PREFIX)gcc

ARCH ?= rv64imafd
ABI ?= lp64d
CMODEL ?= medany
SPECS ?= htif_nano.specs
LDSCRIPT ?= htif.ld
OUTDIR ?= /workspace/output/replacement-benchmarks
COURSE_BENCH_DIR ?= /workspace/course/benchmarks

CFLAGS := -std=gnu99 -O2 -Wall -Wextra -fno-common -fno-builtin-printf \
          -march=$(ARCH) -mabi=$(ABI) -mcmodel=$(CMODEL) \
          -specs=$(SPECS) -I$(COURSE_BENCH_DIR)
LDFLAGS := -static -T $(LDSCRIPT)

# make QUICK=1 builds smaller smoke-test workloads (use a separate OUTDIR).
ifeq ($(QUICK),1)
CFLAGS += -DQUICK
endif

AVAILABLE_BENCHES := reuse streaming stream_pollution capacity conflict phase_change mixed
BENCHES ?= $(AVAILABLE_BENCHES)
ELFS := $(addprefix $(OUTDIR)/,$(addsuffix .riscv,$(BENCHES)))

.PHONY: all clean list
all: $(ELFS)

list:
	@printf '%s\n' $(AVAILABLE_BENCHES)

$(OUTDIR):
	mkdir -p $(OUTDIR)

define BENCH_template
$(OUTDIR)/$(1).riscv: replacement_microbench.c \
    $(COURSE_BENCH_DIR)/metrics.h $(COURSE_BENCH_DIR)/hpm.h | $(OUTDIR)
	$$(CC) $$(CFLAGS) -DWORKLOAD=$(2) $$< -o $$@ $$(LDFLAGS)
endef

$(eval $(call BENCH_template,reuse,1))
$(eval $(call BENCH_template,streaming,2))
$(eval $(call BENCH_template,stream_pollution,3))
$(eval $(call BENCH_template,capacity,4))
$(eval $(call BENCH_template,conflict,5))
$(eval $(call BENCH_template,phase_change,6))
$(eval $(call BENCH_template,mixed,7))

clean:
	rm -f $(ELFS)
