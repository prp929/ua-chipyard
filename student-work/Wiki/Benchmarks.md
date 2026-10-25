# Benchmarks

The L2 replacement benchmark suite is a set of small, purpose-built RISC-V
workloads for comparing cache replacement policies. Each program makes a
particular memory-access pattern easy to inspect, then records core performance
counters and L2 access/miss windows. The suite is meant to expose useful
differences between policies, not to predict application performance.

## Where they came from

These seven workloads were written for this L2 replacement-policy project. They
are not imported from an external benchmark suite. Their patterns are based on
common cache-policy test cases: repeated reuse, sequential streaming, working
sets around cache capacity, same-set conflicts, and changes or mixtures of
access patterns.

The C source is `student-work/l2-replacement/benchmarks/replacement_microbench.c`.
The Makefile builds that one source seven times with a different
`-DWORKLOAD=N` value for each program. The programs use the course environment's
`metrics.h` and `hpm.h` for core/L1 performance counters. The L2
`L2WINDOW` counters and phase markers are added by this project; they are not
provided by the course measurement headers.

## Workloads and their purpose

| Name | Access pattern | What it helps examine |
|---|---|---|
| `reuse` | Repeatedly reads a 32 KiB hot region. | A small working set with temporal reuse; a simple baseline for repeated hits. |
| `streaming` | Sweeps sequentially through 2 MiB. | Scan behavior and the cost of bringing in data that is not reused soon. |
| `stream_pollution` | Reads the hot region, scans 2 MiB, then reads the hot region again. | Whether a scan evicts useful hot lines and how well the policy retains them. |
| `capacity` | Runs separate phases over 32 KiB, 128 KiB, 512 KiB, and 2 MiB regions. | How misses change as a working set grows from small to at/over the assumed L2 capacity. |
| `conflict` | Repeatedly accesses 8, then 12, addresses separated by 64 KiB. | Same-set contention: with the assumed geometry, these addresses map to one set, and 12 lines exceed its 8 ways. |
| `phase_change` | Reads a hot region, streams through 2 MiB, then reads the hot region again. | How a policy behaves when the workload shifts from reuse to a scan and back. |
| `mixed` | Scans the large region while interleaving occasional reads from the hot region. | The trade-off between scan traffic and protecting intermittently reused lines. |

The geometry used by the current configuration is 512 KiB, 8 ways, 1024 sets,
and 64-byte lines. `capacity` and especially `conflict` depend on this geometry;
check the run report's `L2 cache:` line before interpreting results if the
configuration changes.

## Sizes and measurement notes

Normal mode uses a 2 MiB data region, with repetition counts defined in
`replacement_microbench.c`. `QUICK=1` uses a 1 MiB region and roughly one
quarter of the repetitions. The quick region remains larger than the assumed
512 KiB L2, but quick results should only be compared with other quick runs,
not with normal-mode results.

The data array is mapped at `DATA_BASE` (default `0x88000000`) rather than
stored in the ELF's `.bss`. This avoids the simulator's slow zero-fill of a
large `.bss` segment before the workload starts. The DRAM region initially
reads as zero and the benchmark does not initialize or write the array, so its
checksum is always zero. That checksum only provides a basic equality check
between policy runs; it does not validate computed nonzero data.

The suite measures different things at different layers:

- `metrics.h`/`hpm.h` report core and L1 events, including D-cache misses.
- The project cache monitor reports L2 accesses and misses in windows of 64
  accesses. The parser aligns these cycle counts approximately with benchmark
  phase markers, so per-phase L2 counts are estimates.
- The comparison report includes cycles, L2 miss counts, and checksum matching.
  Simulation timing is affected by the simulator and should be interpreted
  alongside the miss counts.

See [How to Run](How%20to%20Run.md) for build and run commands, and
[Assembly and RISC-V](Assembly%20and%20RISC-V.md) for how the C workloads become
loads that reach the cache.
