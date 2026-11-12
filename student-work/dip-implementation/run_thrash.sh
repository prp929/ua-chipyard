#!/bin/bash
# Script to compile and run thrash.riscv across Baseline, TrueLRU, BIP, and DIP configs
set -e

REPO_ROOT=/workspace/chipyard
SIM_DIR="$REPO_ROOT/sims/verilator"
BENCH_DIR="$REPO_ROOT/student-work/dip-implementation/benchmarks"
OUT="$REPO_ROOT/student-work/dip-implementation/results"
mkdir -p "$OUT"

echo "=========================================="
echo "=== Step 1: Compiling thrash.riscv ==="
echo "=========================================="
source "$REPO_ROOT/env.sh"
BMARK_COMMON="$REPO_ROOT/toolchains/riscv-tools/riscv-tests/benchmarks/common"
ENV_DIR="$REPO_ROOT/toolchains/riscv-tools/riscv-tests/env"

riscv64-unknown-elf-gcc -DPREALLOCATE=1 -mcmodel=medany -static -std=gnu99 -O2 \
  -ffast-math -fno-common -fno-builtin-printf -fno-tree-loop-distribute-patterns \
  -march=rv64gc -mabi=lp64d \
  -I"$ENV_DIR" -I"$BMARK_COMMON" \
  -nostdlib -nostartfiles -T "$BMARK_COMMON/test.ld" \
  -o "$BENCH_DIR/thrash.riscv" \
  "$BENCH_DIR/thrash.c" \
  "$BMARK_COMMON/syscalls.c" "$BMARK_COMMON/crt.S" -lgcc

echo "Compilation successful."

if [ -n "$1" ]; then
    CONFIGS=("$1")
else
    CONFIGS=("BaselineConfig" "EceProjectLRUConfig" "EceProjectBIPConfig" "EceProjectDIPConfig")
fi

cd "$SIM_DIR"
for cfg in "${CONFIGS[@]}"; do
    echo "=========================================="
    echo "[$(date +%T)] Running thrash.riscv on $cfg..."
    echo "=========================================="
    ./simulator-chipyard.harness-$cfg "$BENCH_DIR/thrash.riscv" > "$OUT/thrash_${cfg}.log" 2>&1
    echo "[$(date +%T)] Finished $cfg."
    grep -E "mcycle|minstret" "$OUT/thrash_${cfg}.log" || true
done

echo "=========================================="
echo "=== Step 3: Parsing and Summarizing Results ==="
echo "=========================================="
python3 "$REPO_ROOT/student-work/dip-implementation/parse_results.py"
