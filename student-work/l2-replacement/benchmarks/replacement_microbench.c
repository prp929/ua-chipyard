#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#include "metrics.h"

#ifndef WORKLOAD
#error "Build with a WORKLOAD value from benchmarks/Makefile."
#endif

#define CACHE_LINE_WORDS 8u
#define CACHE_LINE_BYTES 64u
#define L2_SET_SPAN_BYTES (CACHE_LINE_BYTES * 1024u)
#ifdef QUICK
/* Smoke-test size: still 2x the 512 KiB L2, but about 4x less work per run. */
#define DATA_BYTES (1u * 1024u * 1024u)
#define REPS(n) (((n) >= 4u) ? ((n) / 4u) : 1u)
#else
#define DATA_BYTES (2u * 1024u * 1024u)
#define REPS(n) (n)
#endif
#define DATA_WORDS (DATA_BYTES / sizeof(uint64_t))
#define DATA_LINES (DATA_WORDS / CACHE_LINE_WORDS)
#define HOT_BYTES (32u * 1024u)
#define HOT_LINES (HOT_BYTES / CACHE_LINE_BYTES)

volatile uint64_t sink;
/* The array is placed at a fixed DRAM address instead of in .bss. A .bss array
 * becomes part of the ELF's loaded segment, and the simulator's loader zero-fills
 * it over a slow serial link before main() runs (minutes per MiB). DRAM is
 * 256 MiB at 0x80000000, so this address stays inside it. DRAM reads as
 * zero until written, so the values are identical for every policy. */
#ifndef DATA_BASE
#define DATA_BASE 0x88000000UL
#endif
#define data ((volatile uint64_t *)DATA_BASE)

static void initialize_data(void)
{
    /* Deliberately empty: see DATA_BASE above. */
}

static inline uint64_t read_line(size_t line)
{
    return data[line * CACHE_LINE_WORDS];
}

__attribute__((unused)) static uint64_t sweep_lines(size_t line_count, unsigned repetitions)
{
    uint64_t sum = 0;
    unsigned rep;
    size_t line;

    for (rep = 0; rep < repetitions; ++rep)
        for (line = 0; line < line_count; ++line)
            sum += read_line(line);
    return sum;
}

__attribute__((unused)) static uint64_t access_conflicting_lines(size_t line_count, unsigned repetitions)
{
    uint64_t sum = 0;
    unsigned rep;
    size_t line;
    const size_t lines_per_set_span = L2_SET_SPAN_BYTES / CACHE_LINE_BYTES;

    for (rep = 0; rep < repetitions; ++rep)
        for (line = 0; line < line_count; ++line)
            sum += read_line(line * lines_per_set_span);
    return sum;
}

/* PHASEWIN gives each phase's absolute mcycle window so the runner can match
 * it to the L2 access log (L2WINDOW lines) printed by the cache. */
#define END_PHASE(name)                                                       \
    do {                                                                      \
        uint64_t _phase_end = rd_mcycle();                                    \
        BENCH_END(name);                                                      \
        printf("PHASEWIN,%s,%lu,%lu\n", (name), (unsigned long)_bench_c0,     \
               (unsigned long)_phase_end);                                    \
    } while (0)

#define RUN_PHASE(name, expression)                                           \
    do {                                                                      \
        BENCH_BEGIN();                                                        \
        uint64_t phase_value = (expression);                                  \
        sink ^= phase_value;                                                  \
        END_PHASE(name);                                                      \
    } while (0)

int main(void)
{
    hpm_setup();
    initialize_data();

#if WORKLOAD == 1
    RUN_PHASE("reuse", sweep_lines(HOT_LINES, REPS(64)));
#elif WORKLOAD == 2
    RUN_PHASE("stream", sweep_lines(DATA_LINES, REPS(3)));
#elif WORKLOAD == 3
    RUN_PHASE("hot_before", sweep_lines(HOT_LINES, REPS(8)));
    RUN_PHASE("stream", sweep_lines(DATA_LINES, REPS(2)));
    RUN_PHASE("hot_after", sweep_lines(HOT_LINES, REPS(8)));
#elif WORKLOAD == 4
    RUN_PHASE("cap_32k", sweep_lines((32u * 1024u) / CACHE_LINE_BYTES, REPS(8)));
    RUN_PHASE("cap_128k", sweep_lines((128u * 1024u) / CACHE_LINE_BYTES, REPS(8)));
    RUN_PHASE("cap_512k", sweep_lines((512u * 1024u) / CACHE_LINE_BYTES, REPS(8)));
    RUN_PHASE("cap_2m", sweep_lines(DATA_LINES, REPS(8)));
#elif WORKLOAD == 5
    RUN_PHASE("ways_8", access_conflicting_lines(8, REPS(128)));
    RUN_PHASE("ways_12", access_conflicting_lines(12, REPS(128)));
#elif WORKLOAD == 6
    RUN_PHASE("reuse_a", sweep_lines(HOT_LINES, REPS(16)));
    RUN_PHASE("stream", sweep_lines(DATA_LINES, REPS(2)));
    RUN_PHASE("reuse_b", sweep_lines(HOT_LINES, REPS(16)));
#elif WORKLOAD == 7
    {
        uint64_t sum = 0;
        unsigned rep;
        size_t line;

        BENCH_BEGIN();
        for (rep = 0; rep < REPS(4); ++rep) {
            for (line = 0; line < DATA_LINES; ++line) {
                if ((line & 15u) == 0)
                    sum += read_line((line >> 4) % HOT_LINES);
                sum += read_line(line);
            }
        }
        sink ^= sum;
        END_PHASE("mixed");
    }
#else
#error "Unknown WORKLOAD value."
#endif

    printf("CHECKSUM,%lu\n", (unsigned long)sink);
    return 0;
}
