#!/bin/bash
# set -e
SIM_DIR=/workspace/chipyard/sims/verilator
BMARKS=/workspace/chipyard/.conda-env/riscv-tools/riscv64-unknown-elf/share/riscv-tests/benchmarks
OUT=/workspace/chipyard/student-work/dip-implementation/results
mkdir -p "$OUT"

CONFIGS=("BaselineConfig" "EceProjectLRUConfig" "EceProjectBIPConfig" "EceProjectDIPConfig")
TESTS=("mm.riscv" "qsort.riscv" "vvadd.riscv" "spmv.riscv" "dhrystone.riscv")

cd "$SIM_DIR"
for cfg in "${CONFIGS[@]}"; do
    echo "=========================================="
    echo "=== Testing Configuration: $cfg ==="
    echo "=========================================="
    for t in "${TESTS[@]}"; do
        echo "[$(date +%T)] Running $t on $cfg..."
        ./simulator-chipyard.harness-$cfg "$BMARKS/$t" > "$OUT/${t}_${cfg}.log" 2>&1
    done
    if [ -f /workspace/chipyard/student-work/dip-implementation/benchmarks/thrash.riscv ]; then
        echo "[$(date +%T)] Running thrash.riscv on $cfg..."
        ./simulator-chipyard.harness-$cfg \
          /workspace/chipyard/student-work/dip-implementation/benchmarks/thrash.riscv \
          > "$OUT/thrash_${cfg}.log" 2>&1
    fi
done
echo "=========================================="
echo "All simulations completed! Results saved to $OUT"
echo "=========================================="
