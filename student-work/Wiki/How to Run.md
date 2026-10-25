# How to Run

Step-by-step guide to running the L2 replacement project and confirming it works. Do the steps in order and stop at the first failure. Status: the patch, Scala and Verilator build for the Random config have been verified in the course container. Random and the Round Robin demo are the only policies that produce data.

Background pages: `Current Project State.md` (what exists), `How Chipyard Works.md` (concepts), `How Make Files Work.md`, `Shell Scripts.md`, `Scala Basics.md` (references).

## 1. Prerequisites

- Docker Desktop installed and running (Windows needs WSL 2).
- This repo cloned, on your working branch.

From the repo root in PowerShell:

```powershell
docker compose pull              # once
docker compose run --name l2dev chipyard   # first time: opens a shell in a NAMED container
docker start -ai l2dev                       # later: re-enter it
```

Use a named container, not `--rm`. A `--rm` container is deleted on exit, taking the patched sources, the Verilator build cache (about 15 min per config) and `/workspace/output` with it; only `student-work/` survives. Alternatively, `run-and-export.ps1` (section 6) manages a persistent container called `l2suite`.

In a fresh interactive shell, load the Chipyard environment before running `make` or the RISC-V compiler (the runner does this itself):

```bash
cd /workspace/chipyard && source env.sh
```

Everything in sections 2-6 runs **inside the container**. `/workspace/student-work` is your repo's `student-work/` folder, so edits on Windows appear in the container immediately.

## 2. Check the container

```bash
ls /workspace                    # expect chipyard, course, student-work (course scripts are at /workspace/course-scripts)
which riscv64-unknown-elf-gcc make python3
```

The runner itself verifies that the InclusiveCache checkout is at the pinned revision `85420cf`, so you do not need to check that by hand.

## 3. Check the pieces separately (cheapest first)

```bash
cd /workspace/student-work/l2-replacement
bash -n run-test-suite              # script syntax check, no output means OK
make -C benchmarks list             # prints the 7 benchmark names
make -C benchmarks                  # compiles them
ls /workspace/output/replacement-benchmarks   # expect 7 .riscv files
```

If these pass, the toolchain, Makefile and benchmark C code are fine.

## 4. Smoke test: one benchmark, Random baseline, Round Robin demo

```bash
BASELINES=L2RandomReplacementConfig \
POLICIES=L2RoundRobinDemoReplacementConfig \
BENCHMARKS=reuse \
bash run-test-suite
```

Settings are comma-separated. Note that an empty value (`POLICIES=`) does not mean "none"; it falls back to the default list, so always name something.

Each config's first build compiles Scala and runs Verilator and takes roughly **15-30 minutes** (about 14 on 4 CPUs). Later runs reuse the build. Each simulation is limited by `SIM_TIMEOUT` seconds (default 7200). Later runs reuse the build. This step proves, in one go:

| Check | Where to look | Healthy result |
|-------|---------------|----------------|
| Patch applies | top of the console output | no errors |
| Scala compiles | `build-L2RandomReplacementConfig.log` in the results directory | no `[error]` lines |
| Simulation runs | `L2RandomReplacementConfig-reuse.log` | program finishes |
| Benchmark prints phases | same log | `PHASEWIN,` lines |
| L2 monitor works | same log | `L2WINDOW,` lines |
| Demo policy builds | `build-L2RoundRobinDemoReplacementConfig.log` | no `[error]`; report has numbers for it |
| Geometry known | `l2-geometry-<config>.txt`, report header `L2 cache:` | sets, ways and size match the 512 KiB / 8-way assumption |

To check that a stub is rejected cleanly, run `POLICIES=L2BIPReplacementConfig`: it is reported as `unsupported` and its build log contains "is not implemented at this revision".

The results directory is printed at the end (`/workspace/output/replacement-suite-<timestamp>`). Useful commands:

```bash
D=$(ls -dt /workspace/output/replacement-suite-* | head -1)
grep -c L2WINDOW "$D"/*-reuse.log
grep PHASEWIN   "$D"/*-reuse.log
grep -i "\[error\]" "$D"/build-*.log | head
cat "$D/comparison.csv"
```

If there are **no `L2WINDOW` lines**, the simulator is not printing hardware `printf` output. The runner passes `+permissive +verbose +permissive-off` (without the `+permissive` wrapper fesvr treats `+verbose` as the program name and fails with "could not open +verbose"). The per-instruction `C0:` trace that `+verbose` produces is filtered out of the saved logs.

### Quick mode

`QUICK=1` builds smaller workloads (1 MiB data set, which is still 2x the 512 KiB L2, and about 4x fewer repetitions) into `/workspace/output/replacement-benchmarks-quick`, so the full-size ELFs are left alone. Use it for smoke tests; the report header shows `Quick mode: 1`. Quick numbers must not be compared against full-size numbers.

A quick `reuse` run for two configs takes about 10 minutes of simulation. The data array sits at a fixed DRAM address instead of `.bss` because the simulator loader writes `.bss` very slowly (a 1-2 MiB array otherwise takes hours). Checksums are therefore always 0; that is expected.

```bash
QUICK=1 BASELINES=L2RandomReplacementConfig POLICIES=L2RoundRobinDemoReplacementConfig BENCHMARKS=reuse \
  bash run-test-suite
```

## 5. Full run

```bash
bash run-test-suite
```

Customize:

```bash
BASELINES=L2RandomReplacementConfig \
POLICIES=L2TrueLRUReplacementConfig,L2BIPReplacementConfig \
BENCHMARKS=reuse,stream_pollution,phase_change \
bash run-test-suite
```

Config names: `L2RandomReplacementConfig`, `L2RoundRobinDemoReplacementConfig`, `L2PLRUReplacementConfig`, `L2TrueLRUReplacementConfig`, `L2BIPReplacementConfig`, `L2DIPReplacementConfig`.
Benchmarks: `reuse`, `streaming`, `stream_pollution`, `capacity`, `conflict`, `phase_change`, `mixed`.

Full-run runtime is dominated by building one simulator per implemented policy; each benchmark run is shorter but can still take minutes under Verilator.

## 6. Where the output goes

| File | Contents |
|------|----------|
| `metrics.csv` | one row per policy x benchmark: cycles, instructions, IPC, L1 misses, L2 accesses and misses |
| `comparison.csv` | each policy against the baseline |
| `build-<config>.log` | simulator build output |
| `<config>-<benchmark>.log` | raw simulation output (core trace removed) |
| `l2-geometry-<config>.txt` | the `[L2Replacement]` line printed at elaboration |
| `run_MM_DD_HH_MM.txt` | formatted report, written to `EXPORT_DIR`, with a `run_<stamp>_files` copy of the whole results directory beside it |

`EXPORT_DIR` defaults to `/workspace/export` if that is mounted and writable, otherwise `student-work/l2-replacement/results/`.

### Report to your Desktop from Windows

From host PowerShell, in the repo root (not inside the container):

```powershell
student-work\l2-replacement\run-and-export.ps1
```

Optional parameters: `-Baselines`, `-Policies`, `-Benchmarks`, `-Reset` (recreate the container). It keeps a persistent container `l2suite` so the build cache survives between runs, and the report appears in `Desktop\Chipyard-UA\Output\run_MM_DD_HH_MM.txt`. Docker must be running. The folder is created if it does not exist.

## 7. What a healthy full result looks like (today)

- **Random** has numbers for all 7 benchmarks: cycles, IPC, L2 accesses, L2 misses.
- **Round Robin demo** also has numbers (a sanity reference for your own policies).
- **PLRU, TrueLRU, BIP, DIP** are `unsupported`, because their stubs are not implemented.
- `comparison.csv` and the formatted report exist.
- Sanity relationships: `streaming` has a high L2 miss rate; `reuse` has a low one; `capacity` miss rate rises with working-set size; `conflict` with 12 same-set addresses misses far more than with 8.

If Random's numbers look implausible (for example `reuse` missing a lot), the cache geometry assumption (512 KiB, 8-way, 1024 sets) may be wrong for the real config. Compare the report header `L2 cache:` line with it and adjust the `conflict` and `capacity` benchmarks if needed.

## 8. After you implement a policy

1. Copy the structure of `replacement/RoundRobinDemo.scala`, then edit your policy's file in `student-work/l2-replacement/replacement/` (remove the `unimplemented(...)` line and the `???` bodies).
2. Run just that policy on one small benchmark first:
   ```bash
   BASELINES=L2RandomReplacementConfig POLICIES=L2TrueLRUReplacementConfig BENCHMARKS=reuse \
   bash run-test-suite
   ```
3. Confirm **correctness before performance**: the build succeeds, the program finishes, and it exits with success. A policy that hangs or corrupts data (for example by choosing an invalid way) shows up as a timeout or wrong output.
4. Check the miss counts are plausible, then run the full benchmark set.

Suggested order: TrueLRU, then BIP, PLRU, and DIP last (it needs both LRU and BIP).

## 9. Troubleshooting

| Symptom | Likely cause and fix |
|---------|----------------------|
| `docker compose` cannot connect to the daemon | Docker Desktop is not running; start it and wait for it to be ready |
| Patch fails to apply | A previous patch is half-applied. Reset: `git -C /workspace/chipyard/generators/rocket-chip-inclusive-cache checkout . && git -C /workspace/chipyard/generators/rocket-chip-inclusive-cache clean -fd` (adjust the path to your Chipyard checkout) |
| `[error]` in a build log | Scala compile error; fix the first one, later ones usually cascade |
| `bad interpreter ... \r` | Windows line endings in a script; run `sed -i 's/\r$//' run-test-suite` |
| `riscv64-unknown-elf-gcc: not found` | You are outside the container, or the toolchain is not on `PATH` |
| Policy row says `unsupported` unexpectedly | The stub still contains the `unimplemented(...)` call |
| `unbound variable` from conda | Harmless noise from `env.sh` deactivate hooks in an interactive shell; the runner suppresses it |
| Report not on the Desktop | You ran `run-test-suite` directly instead of `run-and-export.ps1`; check `student-work/l2-replacement/results/` |
| Run takes forever | Verilator simulation of the full-size benchmarks is slow; run fewer benchmarks, or ask for a reduced-size option |

When asking for help, include the first error from the relevant `build-*.log`, the exact command, and the results directory name.
