# How Chipyard Works

A background guide for a graduate student who knows basic programming and some
computer architecture, but has not used Chipyard, RISC-V, or Chisel. It covers
the concepts and tools you need before working on the L2 replacement-policy
project. For what has actually been built, see `Current Project State.md`.

Sources: the Chipyard 1.13/1.14 documentation (chipyard.readthedocs.io), the
Chisel and Rocket Chip documentation, and this repository's `README.md`,
`USAGE.md`, and `docs/`. Where this guide describes our own setup, it says so.

---

## 1. The big picture

You want to know how a cache behaves when you change its design. You cannot do
that on a real chip, so you do it on a **simulated** one:

1. **Describe** a processor/SoC in a hardware generator language (Chisel).
2. **Generate** Verilog (a conventional hardware description) from that description.
3. **Compile** the Verilog into a cycle-accurate software simulator (Verilator).
4. **Run** a real RISC-V program on the simulated chip and read its performance counters.

**Chipyard** is the framework that ties these steps together. It bundles
processor cores, caches, buses, peripherals, simulators, and build scripts so
that "a whole SoC" is one command: `make CONFIG=<name>`.

Our project changes one piece, the **L2 cache replacement policy**, and measures
the effect.

---

## 2. RISC-V

**RISC-V** (pronounced "risk-five") is an open **instruction set architecture
(ISA)**: the contract between software and hardware that defines the
instructions a processor understands, its registers, and how memory is accessed.
x86 and ARM are other ISAs. RISC-V is open and royalty-free, so anyone can build
a processor for it. RISC stands for *reduced instruction set computer*: a small,
regular instruction set rather than many complex instructions.

Key ideas:

- **Modular.** A small mandatory base plus optional extensions, named by letters:
  - `I` - base integer instructions; `E` - a smaller embedded base.
  - `M` - integer multiply/divide; `A` - atomic memory operations.
  - `F` / `D` - single/double-precision floating point.
  - `C` - compressed (16-bit) instructions.
  - `G` is shorthand for `IMAFD` (plus some housekeeping extensions).
- **Word size.** `RV32` is 32-bit, `RV64` is 64-bit. Rocket in Chipyard is RV64.
  A compiler flag such as `-march=rv64imafd` names the exact ISA a program uses.
  It is paired with an ABI such as `lp64d` (64-bit longs and pointers, doubles
  passed in floating-point registers). Our benchmark Makefile uses
  `-march=rv64imafd -mabi=lp64d`.
- **Privilege modes and CSRs.** Control and status registers (CSRs) expose
  hardware state to software. Two matter to us: `mcycle` (cycle count) and
  `minstret` (instructions retired). Hardware performance monitor counters
  (`mhpmcounter3..`) count events such as cache misses.
- **Assembly view.** Instructions are 32-bit words like `add rd, rs1, rs2`.
  Loads and stores (`ld`, `sd`) are the only instructions that touch memory,
  and those are what exercise the caches.

We will **not** modify the ISA in this project. Compilers for RISC-V are
GCC/LLVM; we use the prebuilt `riscv64-unknown-elf-gcc` in the course image.

---

## 3. Core terms you will see everywhere

| Term | Meaning |
|---|---|
| **RTL** | Register-transfer level: a cycle-by-cycle hardware description (Verilog). |
| **SoC** | System on chip: cores, caches, buses, memory controllers, peripherals. |
| **Tile** | One processor core together with its private L1 caches. |
| **Hart** | A hardware thread (a core's execution context). |
| **Elaboration** | Running the Chisel program that builds the hardware. Errors here appear before Verilog exists. |
| **Generator** | A program that produces hardware and takes parameters. |
| **Config** | A named bundle of parameter settings that selects one design. |
| **ELF / `.riscv`** | The compiled program file the simulator loads. |
| **HTIF / TSI** | Host-target interface: how the simulated chip talks to the simulator for loading programs, `printf`, and exit. |

---

## 4. Computer-architecture background for this project

### Cache hierarchy

A memory hierarchy keeps recently used data in small, fast memories close to
the core. In our design:

- **L1** instruction and data caches are private to each core (inside the Rocket tile).
- **L2** is a larger cache shared on the memory side of the L1s. This is our target.
- Below L2 is main memory (DRAM, modeled by the simulator).

A cache stores **blocks (lines)** of `blockBytes` (64 bytes here). It is split
into **sets**, each holding **ways** (associativity) lines:

```
capacity = sets x ways x blockBytes
```

An address is split into **tag | set index | block offset**. A lookup checks the
tags of all ways in the one selected set. A **hit** finds the line; a **miss**
fetches it from below. If the set is full, a **replacement policy** picks a
**victim** line to evict.

### Replacement policies in this project

- **Random** - evict a pseudo-random way. **This is what the pinned Chipyard
  L2 actually does** (an LFSR picks the victim). There is no PLRU in it.
- **LRU** - evict the least recently used line. A newly inserted line becomes
  most recently used (MRU), protecting it from eviction.
- **PLRU** - an inexpensive approximation of LRU using a tree of bits.
- **BIP (Bimodal Insertion Policy)** - insert most new lines at the LRU
  position and only occasionally (e.g. 1 in 32) at MRU. This stops one-time
  streaming data from evicting useful lines.
- **DIP (Dynamic Insertion Policy)** - uses **set dueling**: a few dedicated
  *leader sets* always use LRU insertion, a few always use BIP, and the rest
  (*followers*) copy whichever leader group is missing less. A saturating
  counter, **PSEL**, tracks that: each leader-set miss moves it one way or the
  other.

The original work is Qureshi, Jaleel, Patt, Steely, and Emer, "Adaptive
Insertion Policies for High Performance Caching," ISCA 2007. Read it.

A key design distinction: a **hit** updates recency (promotes the line), while
an **insertion** (fill) places a new line. LRU treats both the same; BIP and
DIP deliberately treat them differently. Our interface keeps these as separate
events for that reason.

### Metrics

```
IPC     = retired instructions / cycles
MPKI    = misses x 1000 / retired instructions
Speedup = baseline cycles / modified cycles
```

Fewer misses do not automatically mean proportionally fewer cycles, because
other bottlenecks may dominate.

---

## 5. Chisel, FIRRTL, and Verilog

**Chisel** is a hardware construction language embedded in **Scala**. Instead of
writing Verilog by hand, you write a Scala program that *builds* hardware.
This lets you use loops, functions, and classes to generate parameterized
designs.

Basic flavor:

```scala
class Counter(width: Int) extends Module {
  val io = IO(new Bundle { val out = Output(UInt(width.W)) })
  val count = RegInit(0.U(width.W))   // a register, reset to 0
  count := count + 1.U                // next-state logic
  io.out := count
}
```

Things to know:

- `Module` is a hardware block; `Bundle` groups signals; `Reg`/`RegInit` make registers.
- `:=` connects signals. `when (cond) { ... }` is conditional hardware (a mux or enable), not a software `if`.
- Scala `if`/`for` run **at elaboration time** and decide *what hardware exists*. Chisel `when`/`Mux` describe hardware that runs *every cycle*. Mixing these up is the most common beginner error.
- `require(...)` checks elaboration-time conditions; failing it stops the build with a message. We use this for "policy not implemented."
- Chisel emits an intermediate representation, **FIRRTL**, which tools turn into Verilog. In Chipyard 1.14 this step uses CIRCT.

Learn Chisel from the Chisel Bootcamp and the Chisel documentation
(chisel-lang.org).

---

## 6. Configuration: how one codebase makes many chips

Most Chipyard generators use Rocket Chip's parameter system (the `cde`
library). The three pieces:

- **Key (`Field`)** - names a parameter, with a default. Our project defines
  `InclusiveCacheReplacementKey`.
- **Config** - sets keys to values. Fragments are small, additive configs named
  `With<Something>`; full configs are assembled with `++`.
- **`Parameters` object `p`** - passed implicitly into modules, so hardware reads
  settings with `p(SomeKey)`.

Fragments apply **right to left** (the rightmost is the base):

```scala
class L2BIPReplacementConfig extends Config(
  new WithInclusiveCacheReplacementPolicy(InclusiveCacheReplacementPolicy.BIP) ++
  new L2ReplacementBaseConfig
)
```

Inside a fragment, `(site, here, up)` give the final value, the current
level's value, and the value from the config below. A later (left) fragment
overrides an earlier one. This is why all five of our policy configs are
identical except for one fragment.

Chipyard builds a design as a **mixin** ("cake pattern") of traits: the
top-level `DigitalTop` includes many `CanHave...` traits, each optional and a
no-op unless its key is set. This is how peripherals appear or disappear by
config alone.

Chipyard's lookup rule: you pick a config **by class name** (`CONFIG=...`).
List what it knows with `make find-configs` in the simulator directory.

---

## 7. The Chipyard repository

Upstream Chipyard (`ucb-bar/chipyard`) is organized roughly as:

```
chipyard/
├── generators/      hardware generators (each usually a git submodule)
│   ├── chipyard/             top-level SoC glue, configs, DigitalTop
│   ├── rocket-chip/          Rocket core, TileLink, buses, subsystem
│   ├── rocket-chip-inclusive-cache/   the L2 cache  <- our target
│   ├── boom/  shuttle/ ...   other cores
│   ├── testchipip/ icenet/ rocket-chip-blocks/ ...  system components
│   └── gemmini/ ...          accelerators
├── sims/            verilator/ vcs/ firesim/ build directories
├── toolchains/      riscv-tools (gcc, spike, pk), qemu...
├── software/        FireMarshal (Linux workloads), baremetal examples
├── tools/           chisel, firrtl/circt, dsptools...
├── vlsi/            Hammer (physical-design flow)
├── tests/           small bare-metal C test programs
└── scripts/, env.sh, build-setup.sh
```

Main components:

- **Cores:** *Rocket* (in-order, the one we use), *BOOM* (out-of-order),
  *Shuttle*, CVA6, Ibex, and others.
- **Rocket Chip:** a generator library, not just one core. It supplies the
  subsystem, buses (TileLink), and L1 caches.
- **Simulators:** *Verilator* (open source, used here), VCS (commercial), and
  FireSim (FPGA-accelerated, on AWS).
- **Software flow:** bare-metal C programs (what we run) or Linux workloads
  via FireMarshal.

### TileLink and Diplomacy (briefly)

Components in Rocket Chip communicate with **TileLink**, a cache-coherent bus
protocol with channels A (requests), B (probes), C (releases/responses),
D (grants/responses), and E (acks). The wiring between components is
negotiated at elaboration time by a system called **Diplomacy**
(`LazyModule`, nodes), which is why generator code looks different from plain
Chisel. You do not need to master it; our changes stay inside the L2 module.

---

## 8. The L2 cache: the inclusive cache

The L2 in Chipyard is `rocket-chip-inclusive-cache` (SiFive's design, now
maintained at `chipsalliance/rocket-chip-inclusive-cache`). Our project pins
commit `85420cf`. Properties:

- **Inclusive:** it holds a copy of everything held in the L1 caches above it.
- **Banked:** it may be split into banks by address; each bank is one
  scheduler plus its own directory.
- **Source layout:** `design/craft/inclusivecache/src/`. Main files:

| File | Role |
|---|---|
| `InclusiveCache.scala` | Top-level LazyModule; creates banks, control port. |
| `Scheduler.scala` | One bank: wires request sinks, MSHRs, directory, and sources. |
| `Directory.scala` | **Tags, state, and victim selection** (the replacement logic lives here). |
| `MSHR.scala` | Miss Status Holding Registers: state machines handling one outstanding request each; they decide fills and directory writes. |
| `BankedStore.scala` | The data array. |
| `Sink*/Source*.scala` | One per TileLink channel. |
| `Parameters.scala` | Cache geometry and checks. |

How a request flows: an A-channel request enters, the scheduler allocates an
MSHR, the **directory** is read to find a hit or pick a victim, the MSHR
orchestrates probes of L1s, memory fetches, and the write of new directory
state. The directory is where our replacement hooks live: *victim selection*
when a miss needs a way, *accepted hits*, *allocation* when a fill completes,
and *invalidation*.

Default geometry assumed in this project: 512 KiB, 8-way, 64-byte lines,
1024 sets, one bank. Verify against the generated design before trusting it.

---

## 9. Where this course's environment differs from stock Chipyard

Rather than installing Chipyard with Conda, this repository uses **Docker**:

- The image (`ghcr.io/tosiron/ua-chipyard:fall2026-v1`) contains a fixed Chipyard, the RISC-V toolchain, and Verilator.
- `compose.yaml` mounts three folders into the container:
  `student-work/` (read/write, yours), `course/` (read-only instructor files),
  and `scripts/` (read-only startup scripts).
- Instructor configs in `course/configs/` and your `.scala` files under
  `student-work/` are made visible to Chipyard at container start.
- **Policy:** do not modify instructor files. Keep your work under `student-work/`.
  This is why our project patches the cache from a patch file and symlinks its
  files in rather than editing shared files.

Useful course files:

| Path | Purpose |
|---|---|
| `course/configs/CourseRocketConfig.scala` | Defines `BaselineConfig` and `WithBaselineCore`: a minimal in-order Rocket (no branch predictor, no FPU, tiny blocking L1) with 8 performance counters. |
| `course/benchmarks/metrics.h`, `hpm.h` | Measurement harness: prints one row of cycles, instructions, and counters per measured phase. |
| `docs/` | Course tutorials: getting started, running programs, caches, counters, troubleshooting. |
| `USAGE.md` | Command reference. |

---

## 10. Build and run workflow

Inside the container:

```bash
cd /workspace/chipyard
source env.sh                           # set up the toolchain environment
cd sims/verilator
make CONFIG=<ConfigName>                # elaborate -> Verilog -> compile simulator
./simulator-chipyard.harness-<ConfigName> path/to/program.riscv
```

What happens under `make`:

1. **sbt/Scala** compiles the generators and Chisel.
2. **Elaboration** runs your config and emits FIRRTL, then Verilog.
3. **Verilator** converts the Verilog to C++ and compiles `simulator-...`.
4. You run the simulator with a program; it prints the program's output.

Practical notes:

- The first build takes a long time; later builds with the same sources are faster.
- Each config makes its **own simulator binary**, so comparing five policies means five builds.
- Simulation is slow (thousands of times slower than real hardware). Keep benchmarks small.
- Elaboration errors (a failed `require`, a Scala type error) show up before Verilator starts. Read the first error, not the last.
- Pass `+verbose` to the simulator to see `printf` output from the hardware itself. Our L2 miss counter relies on this.

### Writing and running a program

Programs are ordinary C compiled for RISC-V and linked bare-metal:

```bash
riscv64-unknown-elf-gcc -O2 -march=rv64imafd -mabi=lp64d -mcmodel=medany \
    -specs=htif_nano.specs -T htif.ld prog.c -o prog.riscv
```

`htif_nano.specs` provides a tiny C library whose `printf` and exit go through
HTIF. A program can read `mcycle`, `minstret`, and hardware counters directly,
which is how `metrics.h` works.

### Measuring

A **performance counter** counts a hardware event over a window. Reading it
before and after a code region gives the cost of that region. Caveats you
should keep in mind:

- The course counters measure **core and L1 events**; its "cache miss" is the
  D-cache, not L2. Our project adds a separate L2 miss monitor for this reason.
- Warm-up (cold misses, first-touch) differs from steady state; separate them.
- Always compare configurations with identical programs and identical
  everything else, and check that program output (checksum) matches.

---

## 11. How our project fits in

| Piece | Where |
|---|---|
| Replacement interface and hooks | `rocket-chip-inclusive-cache.patch` (applied to the cache checkout) |
| Policy stubs to implement | `student-work/l2-replacement/replacement/` |
| Configs | `student-work/l2-replacement/L2ReplacementConfigs.scala` |
| Benchmarks | `student-work/l2-replacement/benchmarks/` |
| Master runner | `student-work/l2-replacement/run-test-suite` |
| Status and commands | `student-work/Wiki/Current Project State.md` |

A reasonable first path:

1. Read sections 2-6 here, then the Qureshi et al. paper.
2. Follow the repository `README.md` setup and run `docs/getting-started.md`.
3. Build a stock configuration and run a sample program to see the flow work.
4. Read `Directory.scala` and `ReplacementPolicy.scala` (after the patch is applied) until you can explain how a victim is chosen.
5. Run `run-test-suite` for the Random baseline and examine its output.
6. Implement true LRU first (simplest recency order), then BIP, then DIP.
7. Verify each policy for correctness before comparing performance.

---

## 12. Troubleshooting and common pitfalls

- **"Not implemented at this revision"** when building a policy config: expected until you implement it.
- **Config not found:** restart the container so student `.scala` files are re-linked; check with `make find-configs`.
- **Patch won't apply:** the cache checkout already has an older version of the patch; see the reset command in `Current Project State.md`.
- **Slow or hung build:** the first elaboration and Verilator compile are long; check memory limits for Docker.
- **Counters read zero:** the core was built without performance counters (`WithNPerfCounters`), or the event encoding in `hpm.h` does not match the Rocket source. See `docs/performance-counters.md`.
- **Scala vs hardware `if`:** a Scala `if` is decided once at build time; a Chisel `when`/`Mux` is hardware.

---

## 13. Further reading

- Chipyard documentation: https://chipyard.readthedocs.io/
- Chisel: https://www.chisel-lang.org/ and the Chisel Bootcamp
- RISC-V specifications: https://riscv.org/technical/specifications/
- Rocket Chip: https://github.com/chipsalliance/rocket-chip
- TileLink specification (SiFive)
- Inclusive cache: https://github.com/chipsalliance/rocket-chip-inclusive-cache
- Qureshi et al., "Adaptive Insertion Policies for High Performance Caching," ISCA 2007
- Hennessy and Patterson, *Computer Architecture: A Quantitative Approach*, memory hierarchy chapters
