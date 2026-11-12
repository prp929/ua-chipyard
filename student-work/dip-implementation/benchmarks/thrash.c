#include "util.h"
#include <stdint.h>

// 320 KB working set (5 ways per set across 1024 sets * 64 B = 5/4 of 256 KB L2 capacity)
// Fits perfectly for classic cache thrashing (Qureshi et al., ISCA 2007):
// - 4-way associative cache with 5 cache lines per set cyclically accessed.
// - TrueLRU experiences 0% hit rate due to complete thrashing.
// - BIP / DIP retains 4 of 5 lines in the cache, achieving ~80% hit rate.
#define ARRAY_SIZE_BYTES (320 * 1024)
#define CACHE_LINE_BYTES 64
#define WORDS_PER_LINE   (CACHE_LINE_BYTES / sizeof(uint64_t))
#define NUM_ITERATIONS   2

static volatile uint64_t buffer[ARRAY_SIZE_BYTES / sizeof(uint64_t)] __attribute__((aligned(64)));

int main(int argc, char** argv) {
    // Warm-up / initialization pass: populate all lines
    for (uint32_t i = 0; i < ARRAY_SIZE_BYTES / sizeof(uint64_t); i += WORDS_PER_LINE) {
        buffer[i] = (uint64_t)i + 0x12345678ULL;
    }

    setStats(1);

    uint64_t checksum = 0;
    // Cyclic access pattern: 5 lines per set repeatedly accessed
    for (int iter = 0; iter < NUM_ITERATIONS; iter++) {
        for (uint32_t i = 0; i < ARRAY_SIZE_BYTES / sizeof(uint64_t); i += WORDS_PER_LINE) {
            checksum += buffer[i];
        }
    }

    setStats(0);

    // Ensure compiler does not optimize away loop
    if (checksum == 0xdeadbeefULL) {
        return 1;
    }

    return 0;
}
