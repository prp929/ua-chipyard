#!/usr/bin/env python3
"""Format comparison.csv and metrics.csv as an aligned plain-text report.

Usage: format_report.py COMPARISON_CSV METRICS_CSV [HEADER_LINE ...]
"""
import csv
import sys


def table(rows, headers):
    cells = [[str(r.get(h, "")) or "-" for h in headers] for r in rows]
    widths = [max(len(h), *(len(c[i]) for c in cells)) if cells else len(h)
              for i, h in enumerate(headers)]
    def fmt(values):
        return "  ".join(
            v.ljust(w) if i < 3 else v.rjust(w)
            for i, (v, w) in enumerate(zip(values, widths)))
    rule = "  ".join("-" * w for w in widths)
    return "\n".join([fmt(headers), rule] + [fmt(c) for c in cells])


def read(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def main(comparison, metrics, *header):
    cmp_rows = read(comparison)
    met_rows = [r for r in read(metrics) if r["phase"] not in ("CHECKSUM",)]
    for r in met_rows:
        if r["phase"] == "STATUS":
            r["phase"] = "(none)"
    title = "L2 Replacement-Policy Test Report"
    print(title)
    print("=" * len(title))
    for line in header:
        print(line)
    print("\nSUMMARY (one row per benchmark / baseline / policy)")
    print("---------------------------------------------------")
    print(table(cmp_rows, [
        "benchmark", "baseline", "policy", "baseline_cycles", "policy_cycles",
        "cycles_speedup", "baseline_l2_miss", "policy_l2_miss",
        "l2_miss_reduction_pct", "checksum_match", "status"]))
    print("\nPER-PHASE METRICS")
    print("-----------------")
    print(table(met_rows, [
        "benchmark", "config", "phase", "cycles", "instret", "dc_miss",
        "l2_access", "l2_miss", "status"]))
    print("\nNotes: '-' means no value (e.g. unsupported policy). "
          "L2 counts come from L2AccessMonitor in 64-access windows.")


if __name__ == "__main__":
    main(*sys.argv[1:])
