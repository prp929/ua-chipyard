================================================================================
ECE 462/562 PROJECT: 4-TIER L2 CACHE INSERTION POLICY EVALUATION (BASELINE vs. LRU vs. BIP vs. DIP)
================================================================================

1. OVERVIEW & OBJECTIVES
--------------------------------------------------------------------------------
This project implements and evaluates the Dynamic Insertion Policy (DIP) with 
Set Dueling on the RISC-V SoC-level L2 inclusive cache in Chipyard 
(generators/rocket-chip-inclusive-cache), comparing it against static BIP 
(Bimodal Insertion Policy) and baseline TrueLRU as described by Qureshi et al. 
(ISCA 2007), anchored against the course reference minimal Rocket baseline.

The project evaluates four unified configurations:
  1. Course Baseline (BaselineConfig / EceProjectCourseBaselineConfig):
      - Minimal reference Rocket core from course/configs/CourseRocketConfig.scala.
      - In-order 5-stage core without branch prediction (btb = None).
      - No FPU (fpu = None).
      - Slowest legal multiplier/divider (1-bit unroll, ~64 cycles for 64-bit mul).
      - 4 KiB direct-mapped L1 I$ and D$ (64 sets x 1 way x 64 B), blocking (nMSHRs = 0).
      - No L2 cache (uses WithBroadcastManager coherence hub).
  2. TrueLRU (EceProjectLRUConfig):
      - Rocket core + 256 KB 4-way L2 Inclusive Cache (1,024 sets, 64 B line size).
      - Recency tracking using a 6-bit triangular matrix per set via TrueLRU.
      - Cache hits and allocations promote newly accessed/fetched lines to MRU.
      - Replaces the default pseudo-random LFSR victim selection.
  3. Static BIP (EceProjectBIPConfig):
      - Rocket core + 256 KB 4-way L2 Inclusive Cache.
      - Employs a 5-bit wrap counter (bip_counter) providing epsilon = 1/32 throttle.
      - Inserts at MRU with probability 1/32 (when bip_counter == 31).
      - Retains line at LRU position with probability 31/32 (leaves recency matrix 
        unchanged, preventing working-set displacement during streaming passes).
  4. DIP with Set Dueling (EceProjectDIPConfig / EceProjectBaselineConfig):
      - Rocket core + 256 KB 4-way L2 Inclusive Cache.
      - Monitors cache performance across 1,024 sets without extra tag overhead:
          * 32 Dedicated LRU Sets: set[9:5] == set[4:0]
          * 32 Dedicated BIP Sets: set[9:5] == ~set[4:0]
          * 960 Follower Sets: Follow policy winning the duel.
      - 10-bit saturating counter (PSEL) initialized to 512:
          * Increments on misses in dedicated LRU sets.
          * Decrements on misses in dedicated BIP sets.
          * Follower sets choose BIP when PSEL[9] == 1, and LRU when PSEL[9] == 0.


2. FILE LOCATIONS & HOST REPOSITORY STRUCTURE
--------------------------------------------------------------------------------
All project artifacts are organized under student-work/dip-implementation/ 
to maintain clear isolation from other workspace folders:

  ua-chipyard-project/
  └── student-work/
      ├── dip-implementation/
      │   ├── patches/
      │   │   ├── inclusive-cache-dip.patch  (Diff for rocket-chip-inclusive-cache RTL)
      │   │   ├── chipyard-configs.patch     (Diff for RocketConfigs.scala layout)
      │   │   └── rocket-configs.patch       (Backward-compatible patch copy)
      │   ├── src-backups/
      │   │   ├── Directory.scala            (L2 metadata directory with LRU/BIP/DIP logic)
      │   │   ├── Parameters.scala           (Adds policy: String micro-parameter)
      │   │   ├── Configs.scala              (Adds WithInclusiveCacheLRU/BIP/DIP fragments)
      │   │   └── RocketConfigs.scala        (Unified 4-tier SoC configurations)
      │   ├── benchmarks/
      │   │   ├── thrash.c                   (Cache-thrashing C microbenchmark)
      │   │   └── thrash.riscv               (Compiled ELF linked with riscv-tests runtime)
      │   ├── results/
      │   │   ├── summary.txt                (Side-by-side parsed comparison table)
      │   │   └── *.log                      (Per-benchmark raw simulation logs)
      │   ├── run_all.sh                     (Automated full sweep across all 4 configs)
      │   ├── run_thrash.sh                  (Dedicated 1-command thrash runner across policies)
      │   ├── parse_results.py               (Parses logs into side-by-side comparison tables)
      │   └── readme.txt                     (This document)
      └── l2-replacement/                    (Group member directory - untouched)

How to Apply Patches to a Fresh Chipyard Clone:
  cd /workspace/chipyard/generators/rocket-chip-inclusive-cache
  git apply /workspace/chipyard/student-work/dip-implementation/patches/inclusive-cache-dip.patch

  cd /workspace/chipyard
  git apply /workspace/chipyard/student-work/dip-implementation/patches/chipyard-configs.patch


3. AVAILABLE SOC HARDWARE CONFIGURATIONS
--------------------------------------------------------------------------------
Defined in generators/chipyard/src/main/scala/config/RocketConfigs.scala:

  1. BaselineConfig (or EceProjectCourseBaselineConfig):
     - Minimal Rocket Core (no branch predictor, no FPU, 4 KiB direct L1s, no L2).
  2. EceProjectLRUConfig:
     - 1 Huge Rocket Core + 256 KB L2 (4 ways, 1,024 sets) with TrueLRU.
  3. EceProjectBIPConfig:
     - 1 Huge Rocket Core + 256 KB L2 (4 ways, 1,024 sets) with Static BIP.
  4. EceProjectDIPConfig (aliased as EceProjectBaselineConfig):
     - 1 Huge Rocket Core + 256 KB L2 (4 ways, 1,024 sets) with Adaptive DIP Set Dueling.


4. VERILATOR SIMULATION BINARIES
--------------------------------------------------------------------------------
All 4 simulation executables are built and available under sims/verilator/:
  - simulator-chipyard.harness-BaselineConfig
  - simulator-chipyard.harness-EceProjectLRUConfig
  - simulator-chipyard.harness-EceProjectBIPConfig
  - simulator-chipyard.harness-EceProjectDIPConfig
  - simulator-chipyard.harness-EceProjectBaselineConfig (alias to DIP)

To rebuild any simulator from source:
  cd /workspace/chipyard/sims/verilator
  source /workspace/chipyard/env.sh
  make CONFIG=BaselineConfig -j$(nproc)
  make CONFIG=EceProjectLRUConfig -j$(nproc)
  make CONFIG=EceProjectBIPConfig -j$(nproc)
  make CONFIG=EceProjectDIPConfig -j$(nproc)


5. BENCHMARK SUITE & EVALUATION METHODOLOGY
--------------------------------------------------------------------------------

A. Standard Built-In RISC-V Benchmarks:
---------------------------------------
Located at:
  /workspace/chipyard/.conda-env/riscv-tools/riscv64-unknown-elf/share/riscv-tests/benchmarks/

Target Workloads:
  1. mm.riscv: Matrix Multiplication (high locality; LRU-friendly; confirms DIP retains LRU).
  2. qsort.riscv: Quicksort (mixed partitioning, recursion).
  3. vvadd.riscv: Vector-Vector Add (sequential streaming, cache pollution test).
  4. spmv.riscv: Sparse Matrix-Vector Multiply (irregular pointer-chasing).
  5. dhrystone.riscv: Classical integer synthetic baseline.

B. Custom Cache-Thrashing Benchmark (thrash.c):
----------------------------------------------
Located at:
  /workspace/chipyard/student-work/dip-implementation/benchmarks/thrash.c

Why It Matters:
  Standard kernels may fit within the 256 KB L2 cache capacity. To rigorously 
  evaluate DIP against TrueLRU and BIP:
  - Working set: 320 KB (exactly 5 ways per set across all 1,024 sets * 64 B).
  - On a 4-way cache, 5 lines per set cyclically accessed causes:
      * TrueLRU: 0% hit rate (every line evicts the next needed line -> maximum stalls).
      * BIP / DIP: Working set retention preserves 4 of 5 lines -> ~80% hit rate.
      * Set Dueling: 32 dedicated LRU sets detect frequent misses; 32 dedicated BIP 
        sets detect hits. PSEL rapidly shifts to BIP, steering 960 follower sets.

Compiling thrash.c (Reference Only - Handled Automatically by run_thrash.sh):
  thrash.c must be linked with the riscv-tests runtime (test.ld, crt.S, syscalls.c, util.h) 
  so that host-target symbols (tohost/fromhost) and CSR cycle measurement macros 
  (setStats) are mapped. (Note: thrash.riscv is already pre-compiled, and run_thrash.sh 
  will recompile it automatically if you edit thrash.c):

  source /workspace/chipyard/env.sh
  cd /workspace/chipyard
  BMARK_COMMON=toolchains/riscv-tools/riscv-tests/benchmarks/common
  ENV_DIR=toolchains/riscv-tools/riscv-tests/env
  BENCH_DIR=student-work/dip-implementation/benchmarks

  riscv64-unknown-elf-gcc -DPREALLOCATE=1 -mcmodel=medany -static -std=gnu99 -O2 \
    -ffast-math -fno-common -fno-builtin-printf -fno-tree-loop-distribute-patterns \
    -march=rv64gc -mabi=lp64d \
    -I$ENV_DIR -I$BMARK_COMMON \
    -nostdlib -nostartfiles -T $BMARK_COMMON/test.ld \
    -o $BENCH_DIR/thrash.riscv \
    $BENCH_DIR/thrash.c \
    $BMARK_COMMON/syscalls.c $BMARK_COMMON/crt.S -lgcc


6. AUTOMATION SCRIPTS & HOW TO RUN
--------------------------------------------------------------------------------

1. Run the Thrash Benchmark (Single Command):
--------------------------------------------
Inside student-work/dip-implementation/, run the dedicated script:
  cd /workspace/chipyard/student-work/dip-implementation
  ./run_thrash.sh

To run against an individual configuration:
  ./run_thrash.sh EceProjectBIPConfig
  ./run_thrash.sh EceProjectDIPConfig
  ./run_thrash.sh BaselineConfig

2. Run the Full 4-Tier Benchmark Suite:
---------------------------------------
Runs mm, qsort, vvadd, spmv, dhrystone, and thrash across all 4 configurations:
  cd /workspace/chipyard/student-work/dip-implementation
  ./run_all.sh

3. Parse Results:
-----------------
Parses all raw logs in results/ and formats the side-by-side summary table:
  python3 /workspace/chipyard/student-work/dip-implementation/parse_results.py

Results are saved to student-work/dip-implementation/results/summary.txt.


7. GIT WORKFLOW & PR COLLABORATION
--------------------------------------------------------------------------------
1. Current Branch: feature/l2-dip-replacement
   Pull Request: https://github.com/prp929/ua-chipyard/pull/new/feature/l2-dip-replacement

2. Synchronizing on Windows Host Machine:
   cd C:\Users\cemcc\Documents\GitHub\ua-chipyard-project
   git checkout feature/l2-dip-replacement
   git pull origin feature/l2-dip-replacement

3. Committing Updated Readme & Work:
   git add student-work/dip-implementation/
   git commit -m "Update configuration and execution guide"
   git push origin feature/l2-dip-replacement
================================================================================
