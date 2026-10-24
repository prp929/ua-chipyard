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
| Seven microbenchmarks | Done (unbuilt) |
| Master suite runner and report export | Done (unrun) |

Key finding: the pinned InclusiveCache picks victims with an LFSR (random). It
has **no PLRU**, so Random is the real baseline and PLRU is a separate policy
to implement.

## Layout

```
student-work/
├── Wiki/Current Project State.md        this file
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
The benchmarks use the course's `metrics.h`/`hpm.h`, whose counters measure
core and L1 events (the cache-miss event is D-cache, not L2).

## L2 miss counter

`L2AccessMonitor.scala` counts each directory lookup result as an L2 access and
each non-hit as an L2 miss. It prints `L2WINDOW,<cycle>,<accesses>,<misses>`
every 64 accesses when the simulator runs with `+verbose` (the runner passes it).
The benchmarks print `PHASEWIN,<phase>,<start>,<end>`, and `parse_run_log.py`
attributes each L2 window to the phase containing its cycle.

Limits:

- The cache cycle counter and core `mcycle` may be offset slightly, so phase
  boundaries are approximate.
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
4. Add a reduced-size benchmark option if Verilator runs are too slow.
5. Verify functional correctness before trusting any performance comparison.
