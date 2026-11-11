================================================================================
ECE 462/562: L2 INCLUSIVE CACHE TRUE-LRU, BIP, & DIP SET DUELING GUIDE
================================================================================

1. DIRECTORY STRUCTURE IN student-work
--------------------------------------------------------------------------------
All work for this implementation is isolated in:
  /workspace/chipyard/student-work/dip-implementation/

Directory Tree:
  /workspace/chipyard/student-work/
  ├── dip-implementation/
  │   ├── patches/
  │   │   ├── inclusive-cache-dip.patch    (Diff for rocket-chip-inclusive-cache)
  │   │   └── rocket-configs.patch         (Diff for RocketConfigs.scala)
  │   ├── src-backups/
  │   │   ├── Configs.scala                (WithInclusiveCacheLRU/BIP/DIP fragments)
  │   │   ├── Directory.scala              (TrueLRU, BIP, and DIP Set Dueling RTL)
  │   │   ├── Parameters.scala             (InclusiveCacheMicroParameters with policy)
  │   │   └── RocketConfigs.scala          (SoC top-level configurations)
  │   └── readme.txt                       (This guide)
  ├── l2-replacement/                      (Group member's directory)
  └── gitkeep.txt

--------------------------------------------------------------------------------
2. ARCHITECTURAL OVERVIEW
--------------------------------------------------------------------------------
Default Chipyard Inclusive Cache uses a 16-bit pseudo-random LFSR for victim
way selection. This project introduces three deterministic and adaptive policies:

A. Baseline TrueLRU (EceProjectLRUConfig)
   - Cache geometry: 256 KB, 4 ways, 64-byte blocks -> 1,024 sets.
   - For 4 ways, pairwise recency requires W*(W-1)/2 = 6 bits per set.
   - Managed via freechips.rocketchip.util.TrueLRU.
   - Cache hits promote the accessed way to MRU.
   - Cache misses insert the allocated line at MRU.

B. Bimodal Insertion Policy / BIP (EceProjectBIPConfig)
   - Uses a 5-bit saturating wrap counter (bip_counter).
   - Throttle factor epsilon = 1/32:
     * When bip_counter == 31: line is inserted at MRU.
     * When bip_counter != 31 (31/32 of the time): line is inserted at LRU
       by preserving the current 6-bit recency state.
   - Protects cache from thrashing when working set exceeds cache capacity.

C. Dynamic Insertion Policy / DIP with Set Dueling (EceProjectBaselineConfig)
   - Dynamically selects between LRU and BIP using Set Dueling across 1,024 sets:
     * 32 Dedicated LRU sets: set[9:5] == set[4:0]
     * 32 Dedicated BIP sets: set[9:5] == ~set[4:0]
     * 960 Follower sets: !is_lru_set && !is_bip_set
   - 10-bit saturating counter PSEL (initialized to 512):
     * Increments on misses in dedicated LRU sets (saturates at 1023).
     * Decrements on misses in dedicated BIP sets (saturates at 0).
   - Follower decision: Follower sets select BIP when psel[9] == 1,
     and select LRU when psel[9] == 0.

--------------------------------------------------------------------------------
3. HOW TO APPLY THE PATCHES TO A CLEAN CHIPYARD CLONE
--------------------------------------------------------------------------------
To restore or apply these changes in a fresh environment:

  # 1. Apply inclusive cache directory modifications
  cd /workspace/chipyard/generators/rocket-chip-inclusive-cache
  git apply /workspace/chipyard/student-work/dip-implementation/patches/inclusive-cache-dip.patch

  # 2. Apply top-level SoC configuration additions
  cd /workspace/chipyard
  git apply /workspace/chipyard/student-work/dip-implementation/patches/rocket-configs.patch

--------------------------------------------------------------------------------
4. COMPILING SIMULATORS IN sims/verilator
--------------------------------------------------------------------------------
Activate the environment:
  source /workspace/chipyard/env.sh
  cd /workspace/chipyard/sims/verilator

Compile the three comparison configurations:

  # 1. Dynamic Insertion Policy (DIP with Set Dueling)
  make CONFIG=EceProjectBaselineConfig -j$(nproc)

  # 2. Baseline TrueLRU
  make CONFIG=EceProjectLRUConfig -j$(nproc)

  # 3. Static BIP
  make CONFIG=EceProjectBIPConfig -j$(nproc)

Binaries will be placed in /workspace/chipyard/sims/verilator/ with prefix
simulator-chipyard.harness-*.

--------------------------------------------------------------------------------
5. WRITING, COMPILING, AND RUNNING BENCHMARKS
--------------------------------------------------------------------------------
Create a cache-thrashing benchmark that exceeds 256 KB (e.g., 512 KB array):

  mkdir -p /workspace/chipyard/student-work/dip-implementation/benchmarks
  mkdir -p /workspace/chipyard/student-work/dip-implementation/results

Compile C benchmark using RISC-V GCC:
  riscv64-unknown-elf-gcc -O2 -static \
    -o /workspace/chipyard/student-work/dip-implementation/benchmarks/thrash.riscv \
    /workspace/chipyard/student-work/dip-implementation/benchmarks/thrash.c

Run across all three configurations:

  # Run TrueLRU Baseline
  ./simulator-chipyard.harness-EceProjectLRUConfig \
    /workspace/chipyard/student-work/dip-implementation/benchmarks/thrash.riscv \
    > /workspace/chipyard/student-work/dip-implementation/results/lru_run.log 2>&1

  # Run Static BIP
  ./simulator-chipyard.harness-EceProjectBIPConfig \
    /workspace/chipyard/student-work/dip-implementation/benchmarks/thrash.riscv \
    > /workspace/chipyard/student-work/dip-implementation/results/bip_run.log 2>&1

  # Run DIP Set Dueling
  ./simulator-chipyard.harness-EceProjectBaselineConfig \
    /workspace/chipyard/student-work/dip-implementation/benchmarks/thrash.riscv \
    > /workspace/chipyard/student-work/dip-implementation/results/dip_run.log 2>&1

--------------------------------------------------------------------------------
6. GIT & PULL REQUEST WORKFLOW
--------------------------------------------------------------------------------
To update your open PR from your Windows host:
  cd C:\Users\cemcc\Documents\GitHub\ua-chipyard-project
  git add student-work/dip-implementation/
  git commit -m "Organize DIP implementation files and add tutorial readme"
  git push

================================================================================
