#!/usr/bin/env python3
"""Turn one simulator log into metrics.csv rows.

Usage: parse_run_log.py ROLE CONFIG BENCHMARK STATUS LOG
       parse_run_log.py --status ROLE CONFIG BENCHMARK STATUS

Inputs found in the log:
  * the metrics.h phase table (core/L1 counters),
  * PHASEWIN,<phase>,<start>,<end>  absolute mcycle window of each phase,
  * L2WINDOW,<cycle>,<accesses>,<misses>  from the cache (needs +verbose).
An L2 window is attributed to the phase whose (start, end] contains its cycle.
"""
import csv
import re
import sys

HPM_FIELDS = (
    "cycles", "instret", "branch", "load", "mul", "bp_dir_ms",
    "bp_tgt_ms", "md_stall", "ic_miss", "dc_miss",
)
COLUMNS = (
    ("role", "config", "benchmark", "phase") + HPM_FIELDS +
    ("l2_access", "l2_miss", "checksum", "status")
)


def parse(role, config, benchmark, status, log_path):
    windows = {}
    l2 = []
    rows = {}
    checksum = ""
    with open(log_path, errors="replace") as log:
        for line in log:
            if (m := re.search(r"PHASEWIN,(\w+),(\d+),(\d+)", line)):
                windows[m.group(1)] = (int(m.group(2)), int(m.group(3)))
            elif (m := re.search(r"L2WINDOW,\s*(\d+),\s*(\d+),\s*(\d+)", line)):
                l2.append(tuple(int(g) for g in m.groups()))
            elif (m := re.match(r"CHECKSUM,(\d+)", line)):
                checksum = m.group(1)
            else:
                tokens = line.split()
                if (len(tokens) == 1 + len(HPM_FIELDS) and
                        all(t.isdigit() for t in tokens[1:])):
                    rows[tokens[0]] = tokens[1:]

    offset = clock_offset(l2, rows, windows)
    out = []
    for phase, values in rows.items():
        if phase not in windows:
            continue
        start, end = (w + offset for w in windows[phase])
        access = sum(a for c, a, _ in l2 if start < c <= end) if l2 else ""
        miss = sum(x for c, _, x in l2 if start < c <= end) if l2 else ""
        out.append([role, config, benchmark, phase, *values, access, miss, "", status])
    if checksum:
        out.append([role, config, benchmark, "CHECKSUM"] +
                   [""] * (len(HPM_FIELDS) + 2) + [checksum, status])
    if not rows:
        out.append(status_row(role, config, benchmark, status))
    return out


def clock_offset(l2, rows, windows, step=250):
    """The cache counts cycles from reset, mcycle from core release (after the
    program load). Pick the shift that makes each phase's L2 accesses best match
    its L1 miss count (ic_miss + dc_miss)."""
    if not l2:
        return 0
    idx = {name: i for i, name in enumerate(HPM_FIELDS)}
    targets = []
    for phase, values in rows.items():
        if phase in windows:
            want = int(values[idx["ic_miss"]]) + int(values[idx["dc_miss"]])
            targets.append((windows[phase], want))
    if not targets:
        return 0
    best, best_cost = 0, None
    for offset in range(0, l2[-1][0], step):
        cost = 0
        for (s, e), want in targets:
            got = sum(a for c, a, _ in l2 if s + offset < c <= e + offset)
            cost += abs(got - want)
        if best_cost is None or cost < best_cost:
            best, best_cost = offset, cost
    return best


def status_row(role, config, benchmark, status):
    return ([role, config, benchmark, "STATUS"] +
            [""] * (len(HPM_FIELDS) + 3) + [status])


def main(argv):
    writer = csv.writer(sys.stdout, lineterminator="\n")
    if argv[0] == "--status":
        writer.writerow(status_row(*argv[1:5]))
    else:
        writer.writerows(parse(*argv[:5]))


if __name__ == "__main__":
    main(sys.argv[1:])
