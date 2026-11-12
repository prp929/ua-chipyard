#!/usr/bin/env python3
import glob
import os
import re
from collections import defaultdict

results_dir = "/workspace/chipyard/student-work/dip-implementation/results"
output_file = os.path.join(results_dir, "summary.txt")

# Target configurations in order
CONFIG_KEYS = [
    ("BaselineConfig", "Baseline (No L2)"),
    ("EceProjectLRUConfig", "LRU (256KB)"),
    ("EceProjectBIPConfig", "BIP (256KB)"),
    ("EceProjectDIPConfig", "DIP (256KB)"),
]

# Aliases for backwards compatibility
CONFIG_ALIASES = {
    "EceProjectBaselineConfig": "EceProjectDIPConfig",
}

files = sorted(glob.glob(os.path.join(results_dir, "*.log")))

# Data structure: data[benchmark][config] = {'cycles': ..., 'inst': ...}
data = defaultdict(dict)
all_benchmarks = set()

for f in files:
    base = os.path.basename(f)
    if base in ["all_logs.txt", "summary.txt"]:
        continue
    base = base.replace(".log", "")

    # Match benchmark and config
    cfg_found = None
    bmark = None

    for target_cfg, _ in CONFIG_KEYS:
        if base.endswith("_" + target_cfg):
            bmark = base[:-(len(target_cfg) + 1)]
            cfg_found = target_cfg
            break

    if not cfg_found:
        for alias_cfg, target_cfg in CONFIG_ALIASES.items():
            if base.endswith("_" + alias_cfg):
                bmark = base[:-(len(alias_cfg) + 1)]
                cfg_found = target_cfg
                break

    if not cfg_found:
        if "_lru" in base:
            bmark = base.replace("_lru", "")
            cfg_found = "EceProjectLRUConfig"
        else:
            continue

    try:
        with open(f, "r", errors="ignore") as fp:
            content = fp.read()
    except Exception:
        content = ""

    cycles = "ABORT/ERR"
    inst = "N/A"

    m_cyc = re.search(r"mcycle\s*=\s*(\d+)", content)
    m_inst = re.search(r"minstret\s*=\s*(\d+)", content)
    c_cyc = re.search(r"C0:\s*(\d+)\s*cycles", content)
    c_inst = re.search(r"C0:\s*(\d+)\s*instructions", content)

    if m_cyc:
        cycles = m_cyc.group(1)
    elif c_cyc:
        cycles = c_cyc.group(1)

    if m_inst:
        inst = m_inst.group(1)
    elif c_inst:
        inst = c_inst.group(1)

    all_benchmarks.add(bmark)
    # Prefer non-error or newer entry
    if cfg_found not in data[bmark] or data[bmark][cfg_found]['cycles'] == "ABORT/ERR":
        data[bmark][cfg_found] = {'cycles': cycles, 'inst': inst}

# Remove duplicate bare names if .riscv version exists
cleaned_bmarks = set()
for b in all_benchmarks:
    if not b.endswith(".riscv") and (b + ".riscv") in all_benchmarks:
        continue
    cleaned_bmarks.add(b)

standard_order = ["dhrystone.riscv", "mm.riscv", "qsort.riscv", "spmv.riscv", "vvadd.riscv", "thrash"]
sorted_bmarks = sorted(list(cleaned_bmarks), key=lambda x: (standard_order.index(x) if x in standard_order else 999, x))


lines = []
lines.append("=" * 104)
lines.append("ECE 462/562 PROJECT: 4-TIER REPLACEMENT POLICY EVALUATION SUMMARY (CYCLES)")
lines.append("=" * 104)

# Side-by-side header
hdr = f"{'Benchmark':<20}"
for cfg, label in CONFIG_KEYS:
    hdr += f" {label:<20}"
lines.append(hdr)
lines.append("-" * 104)

for bmark in sorted_bmarks:
    row = f"{bmark:<20}"
    for cfg, _ in CONFIG_KEYS:
        val = data[bmark].get(cfg, {}).get('cycles', 'PENDING')
        row += f" {val:<20}"
    lines.append(row)

lines.append("=" * 104)
lines.append("")
lines.append("=" * 104)
lines.append("DETAILED BREAKDOWN (CYCLES & INSTRUCTIONS)")
lines.append("=" * 104)
det_hdr = f"{'Benchmark':<20} {'Configuration':<28} {'Cycles (mcycle)':<18} {'Instret (minstret)':<18}"
lines.append(det_hdr)
lines.append("-" * 104)

for bmark in sorted_bmarks:
    for cfg, label in CONFIG_KEYS:
        info = data[bmark].get(cfg, {'cycles': 'PENDING', 'inst': 'PENDING'})
        lines.append(f"{bmark:<20} {cfg:<28} {info['cycles']:<18} {info['inst']:<18}")

output_text = "\n".join(lines) + "\n"

with open(output_file, "w") as out_fp:
    out_fp.write(output_text)

print(output_text)
print(f"Successfully generated summary table at: {output_file}")
