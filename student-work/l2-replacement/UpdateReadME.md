# L2 Replacement-Policy Project

All files for this student project are kept under
`student-work/l2-replacement/`, following the repository README's student-work
policy. No instructor config, docs, or shared scripts need to be changed.

This project targets Chipyard 1.14.0 and
`chipsalliance/rocket-chip-inclusive-cache` commit `85420cf`.

## Current scope

The pinned InclusiveCache source selects victims using an LFSR; it does not
implement PLRU. The runner keeps that existing random policy as the default.
PLRU, true LRU, BIP, and DIP are selector options with clear elaboration errors
until their algorithms are implemented.

The cache patch adds a shared policy interface and distinct hooks for victim
selection, accepted hits, allocation/fill, and invalidation. BIP settings
include its MRU probability, and DIP settings include leader-set count per
policy and PSEL width. No LRU, BIP, or DIP algorithm is implemented yet.

## Run a test suite

Start the container normally:

```bash
docker compose run --rm chipyard
```

Inside the container, run the master script:

```bash
bash /workspace/student-work/l2-replacement/run-test-suite
```

The script verifies the pinned InclusiveCache revision, applies its local
patch idempotently, links this project's Scala configuration without replacing
an existing different config file, builds benchmarks once, and loops over the
selected configs and benchmark programs. This explicit link is needed because
the shared course setup only links instructor configurations. Logs, raw phase
metrics, and policy comparisons go to a timestamped directory under
`/workspace/output`.

By default it compares the implemented Random baseline against each policy
variant on `isp_bench`. Customize the pool with comma-separated environment
variables:

```bash
BASELINES=L2RandomReplacementConfig \
POLICIES=L2PLRUReplacementConfig,L2BIPReplacementConfig \
BENCHMARKS=isp_bench \
bash /workspace/student-work/l2-replacement/run-test-suite
```

Available configs:

| Config | Policy | Current status |
|---|---|---|
| `L2RandomReplacementConfig` | Existing LFSR random | Implemented |
| `L2PLRUReplacementConfig` | PLRU | Selector only |
| `L2TrueLRUReplacementConfig` | True LRU | Selector only |
| `L2BIPReplacementConfig` | BIP | Selector only |
| `L2DIPReplacementConfig` | DIP | Selector only |

At present the non-Random configs are recorded as unsupported, not measured.
The report includes cycles and D-cache misses from existing HPM events; these
are not L2 miss counts. Add an L2 counter before interpreting the CSV as an
L2-miss comparison.
