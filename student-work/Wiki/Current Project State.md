# Current Project State

L2 cache replacement-policy project for Chipyard 1.14.0, targeting
`chipsalliance/rocket-chip-inclusive-cache` pinned at commit `85420cf`.
All project files live under `student-work/l2-replacement/`, following the
repository README's student-work policy. No instructor config, docs, or shared
scripts are modified.

**Status: scaffolding complete; Random (baseline) and a Round Robin demo are the
only working policies.** The patch applies, and the Scala and Verilator builds
for the Random config succeeded in the course container. The first end-to-end
simulation with the corrected `+permissive` flags is being validated; see
"Next steps". PLRU, TrueLRU, BIP and DIP are still stubs.

## What exists

| Area | State |
|---|---|
| Policy selector (Random, PLRU, TrueLRU, BIP, DIP) | Done; Random is the default |
| Shared replacement interface with separate hit / allocation / invalidation / victim hooks | Done |
| BIP/DIP settings (MRU probability, leader sets per policy, PSEL width) with geometry validation | Done |
| Six comparable simulation configs (five policies + Round Robin demo) | Done |
| Random policy (existing LFSR victim selection, bit-for-bit) | Implemented |
| Round Robin demo (`RoundRobinDemo.scala`) | Fully implemented example to copy from |
| PLRU, true LRU, BIP, DIP | **Stubs only** (fail elaboration with a clear message) |
| L2 miss counter | Done (simulation-only, unverified) |
| Seven microbenchmarks (plus `QUICK=1` smaller variants) | Done; quick mode tested on `reuse` |
| Master suite runner and report export | Done; end-to-end run being validated |

Key finding: the pinned InclusiveCache picks victims with an LFSR (random). It
has **no PLRU**, so Random is the real baseline and PLRU is a separate policy
to implement.

## Layout

```
student-work/
├── Wiki/                                documentation (see "Wiki pages" below)
└── l2-replacement/
    ├── rocket-chip-inclusive-cache.patch   interface, hooks, validation, L2 monitor
    ├── L2ReplacementConfigs.scala          the six configs
    ├── replacement/                        policy stubs (edit these) + demo
    │   ├── RoundRobinDemo.scala            complete worked example
    │   ├── PLRUReplacement.scala
    │   ├── TrueLRUReplacement.scala
    │   ├── BIPReplacement.scala
    │   └── DIPReplacement.scala
    ├── benchmarks/                         Makefile + replacement_microbench.c
    ├── run-test-suite                      master runner (runs in container)
    ├── parse_run_log.py                    simulator log -> metrics rows
    ├── format_report.py                    CSVs -> aligned text report
    ├── run-and-export.ps1                  host script: run + write to Desktop
    └── compose.export.yaml                 compose override mounting the output folder
```

## Wiki pages

| Page | Read it for |
|---|---|
| `Current Project State.md` | This page: what is done, what is not |
| `How to Run.md` | Commands to build, run and check the project (including quick mode) |
| `Benchmarks.md` | Workload patterns, intended purpose, origins, and measurement caveats |
| `How Chipyard Works.md` | How Chipyard, configs and generators fit together |
| `How Make Files Work.md` | Reading the Makefiles used by Chipyard and the benchmarks |
| `Scala Basics.md` | Scala and Chisel syntax needed to write a policy |
| `RTL and Verilog.md` | What hardware description is, how Chisel becomes Verilog, and how Verilator simulates it |
| `Assembly and RISC-V.md` | RISC-V instructions, the benchmark binaries, and how loads reach the L2 |
| `Shell Scripts.md` | Reading and writing the bash scripts such as `run-test-suite` |

## Cache integration (the patch)

`rocket-chip-inclusive-cache.patch` is applied idempotently to the InclusiveCache
checkout by the runner. It:

- Adds `ReplacementPolicy.scala`: the policy enum, config case class and key,
  the `InclusiveCacheReplacementState` interface, the Random implementation,
  and a factory that never substitutes one policy for another.
- Hooks `Directory.scala` for **victim selection**, **accepted hits**,
  **allocation/fill**, and **invalidation**. Hits and insertions are separate
  events so BIP/DIP can treat them differently. `MSHR.scala` marks directory
  writes as fills or invalidations.
- Validates settings in `Parameters.scala`: BIP probability must be a rational
  in [0, 1]; DIP needs at least one follower set and a PSEL width of 1 to 31.
- Adds `L2AccessMonitor.scala` (see below).

The runner also symlinks `replacement/*.scala` and the config file into the
Chipyard tree, refusing to overwrite any different existing file.

## Policies

| Config | Policy | Status |
|---|---|---|
| `L2RandomReplacementConfig` | Existing LFSR random | Implemented |
| `L2RoundRobinDemoReplacementConfig` | Round Robin (teaching example) | Implemented |
| `L2PLRUReplacementConfig` | PLRU | Stub |
| `L2TrueLRUReplacementConfig` | True LRU | Stub |
| `L2BIPReplacementConfig` | BIP | Stub |
| `L2DIPReplacementConfig` | DIP | Stub |

All configs share the same core, 8 HPM counters, and default L2 geometry.
Assumed geometry: 512 KiB, 8-way, 64-byte lines, 1024 sets, one bank.

To implement a policy, edit its file in `l2-replacement/replacement/`: delete
the `InclusiveCacheReplacementState.unimplemented(...)` line and replace the
`???` bodies. Each file's TODO describes what the policy needs. Selecting a
stub still fails elaboration, and the runner records it as `unsupported`.

## Running the suite

Inside the container:

```bash
bash /workspace/student-work/l2-replacement/run-test-suite
```

From host PowerShell (also saves a report to the Desktop):

```powershell
student-work\l2-replacement\run-and-export.ps1
```

The runner checks the pinned revision, applies the patch, links the configs and
stubs, builds the microbenchmarks once, then builds each config's simulator and
runs every benchmark. Customize with comma-separated variables:

```bash
BASELINES=L2RandomReplacementConfig \
POLICIES=L2PLRUReplacementConfig,L2BIPReplacementConfig \
BENCHMARKS=reuse,stream_pollution,phase_change \
bash /workspace/student-work/l2-replacement/run-test-suite
```

Random and Round Robin yield data; PLRU, TrueLRU, BIP and DIP rows are `unsupported`.

## Microbenchmarks

| Benchmark | Pattern |
|---|---|
| `reuse` | Repeated use of a 32 KiB region |
| `streaming` | Stream through 2 MiB |
| `stream_pollution` | Hot data before and after a 2 MiB scan |
| `capacity` | 32 KiB, 128 KiB, 512 KiB, 2 MiB working sets |
| `conflict` | 8 vs 12 same-set addresses (64 KiB stride) |
| `phase_change` | Reuse, stream, then reuse |
| `mixed` | Hot accesses interleaved with streaming |

`conflict` depends on the assumed cache geometry; adjust it if that changes.

**Quick mode:** `QUICK=1 bash run-test-suite` builds smaller versions (1 MiB data
set, about a quarter of the repetitions) into
`/workspace/output/replacement-benchmarks-quick` for fast smoke tests. The report
header shows `Quick mode: 1`. Never compare quick results with full-size ones.
Tested on `reuse` only (Random vs RoundRobin): about 5 minutes of wall time,
about 1.04M simulated cycles.

**Data array placement (important):** the Verilator program loader writes the
whole ELF LOAD segment, including zero-filled `.bss`, at roughly 1.4k simulated
cycles/s, so a 1-2 MiB `.bss` array took tens of minutes to hours (or never
finished). The array now lives at a fixed DRAM address (`DATA_BASE`, default
`0x88000000`; DRAM is 256 MiB at `0x80000000`) and is not in the ELF
(about 9 KB). `initialize_data()` is empty because DRAM reads as zero, so the
checksum is always 0. It still proves policies don't change results, but it is
a weak check. `CHECKSUM` prints with `%lu` because newlib-nano printf does not
support `%llu`.
The benchmarks use the course's `metrics.h`/`hpm.h`, whose counters measure
core and L1 events (the cache-miss event is D-cache, not L2).

## L2 miss counter

`L2AccessMonitor.scala` counts each directory lookup result as an L2 access and
each non-hit as an L2 miss. It prints `L2WINDOW,<cycle>,<accesses>,<misses>`
every 64 accesses when the simulator runs with `+verbose` (the runner passes it).
The benchmarks print `PHASEWIN,<phase>,<start>,<end>`, and `parse_run_log.py`
attributes each L2 window to the phase containing its cycle.

Limits:

- The cache cycle counter counts from reset while `mcycle` counts from core
  release (after the program load), so they differ by a large offset.
  `parse_run_log.py` (`clock_offset()`) searches for the shift that makes each
  phase's L2 accesses best match its L1 miss count (`ic_miss + dc_miss`). Phase
  boundaries are therefore approximate, and the attribution assumes L1 misses
  roughly equal L2 accesses. Quick `reuse` check: 8192 L2 accesses vs 8195
  D-cache misses.
- The window regex allows padding spaces (`L2WINDOW,     13944, 64,  8`).
- A trailing partial 64-access window is dropped.
- The counter is simulation-only (printf) and does not affect cache behavior.
- `+verbose` is passed inside `+permissive ... +permissive-off` (fesvr otherwise treats it as the program name). It also enables a per-instruction core trace, which the runner filters out of the log.
- The elaborated geometry is printed as `[L2Replacement] policy=... sets=... ways=...` and copied into the report header (`L2 cache:`); verify it matches the assumed 512 KiB / 8-way.

## Output

- Working files: a timestamped directory under `/workspace/output` with build
  and run logs, `metrics.csv` (per-phase rows including `l2_access`/`l2_miss`),
  and `comparison.csv` (one row per benchmark, baseline, policy: cycles,
  speedup, D-cache and L2 misses, L2 MPKI, miss reduction, checksum match,
  status).
- Report: `run_MM_DD_HH_MM.txt`, an aligned summary table plus per-phase table.
  The whole results directory is also copied to `run_<stamp>_files` next to the report. `run-and-export.ps1` bind-mounts `Desktop\Chipyard-UA\Output` at
  `/workspace/export` via `compose.export.yaml` (an override; the shared
  `compose.yaml` is unchanged). Without that mount the report goes to
  `student-work/l2-replacement/results/` (gitignored).

If an earlier version of the patch is already applied to the InclusiveCache
checkout, reset it first so the runner can apply the current one:

```bash
git -C generators/rocket-chip-inclusive-cache checkout . && git -C generators/rocket-chip-inclusive-cache clean -fd
```

## Next steps

1. Confirm the Random baseline and Round Robin demo simulate and `+verbose` produces `L2WINDOW` lines.
2. Check the metrics/comparison CSVs against real simulator output.
3. Implement true LRU, then BIP, PLRU, and DIP in `replacement/`.
4. Run the other six benchmarks in quick mode, then time a full-size run (now that the array is out of `.bss`).
5. Verify functional correctness before trusting any performance comparison.
