# RTL and Verilog

You will write the replacement policy in Chisel (Scala), but the thing that is
simulated is Verilog. This page explains the hardware ideas behind that, so the
build logs and the policy code make sense. For Scala syntax see `Scala Basics.md`;
for the build flow see `How Chipyard Works.md`.

## 1. Hardware is not software

A program runs one instruction after another. Hardware is a set of circuits that
**all work at the same time**, every clock cycle.

- A line like `y = a + b` in hardware means "build an adder, permanently wired
  from `a` and `b` to `y`". There is no "execution order".
- Time only exists through the **clock**. Each rising clock edge, storage
  elements capture new values.
- A `for` loop in hardware code usually means "make N copies of this circuit",
  not "repeat N times at run time".

## 2. RTL: Register-Transfer Level

RTL describes a design as:

1. **Registers** (state that holds a value between clock edges).
2. **Combinational logic** (gates and wires that compute the next value from the
   current values, with no memory of their own).

Every cycle: registers feed combinational logic, and the logic outputs are
captured by the registers on the next clock edge.

| Term | Meaning |
|---|---|
| Wire / signal | A connection carrying a value; has a bit width |
| Combinational | Output depends only on current inputs, updates "instantly" |
| Sequential | Depends on stored state; changes at the clock edge |
| Register (`Reg`) | A flip-flop group holding state across cycles |
| Memory (`Mem`, `SyncReadMem`) | An array of storage, like SRAM |
| Clock | The signal that paces all state changes |
| Reset | A signal that puts all state in a known starting value |
| Bit width | Number of bits; `UInt(3.W)` holds 0 to 7 |

## 3. Verilog in one page

Verilog is the traditional language for RTL. You will not write it, but you will
see it in generated files.

```verilog
module Counter(input clock, input reset, output [3:0] count);
  reg [3:0] value;                  // 4-bit register
  assign count = value;             // combinational wire
  always @(posedge clock) begin     // runs at each rising clock edge
    if (reset) value <= 4'd0;
    else       value <= value + 4'd1;
  end
endmodule
```

- `module` is a hardware block with ports; modules instantiate other modules.
- `assign` is combinational logic.
- `always @(posedge clock)` describes registers; `<=` is the non-blocking
  assignment, meaning "take this value at the clock edge".
- `4'd1` means a 4-bit decimal 1. `[3:0]` is a 4-bit vector.

## 4. Chisel: Verilog generated from Scala

Chipyard's hardware (Rocket core, InclusiveCache) is written in **Chisel**, a
library that lets Scala code *generate* hardware. The same counter:

```scala
class Counter extends Module {
  val count = IO(Output(UInt(4.W)))
  val value = RegInit(0.U(4.W))   // register, reset value 0
  value := value + 1.U            // next-state logic
  count := value
}
```

| Chisel | Hardware meaning |
|---|---|
| `UInt(n.W)` | An n-bit unsigned value |
| `Wire(...)` | A combinational signal |
| `Reg(...)` / `RegInit(v)` | A register, with optional reset value |
| `Mem(n, t)` | n entries of type t; asynchronous read, synchronous write |
| `:=` | Connect a signal (last connection wins) |
| `when(c) {...}.otherwise {...}` | A multiplexer (hardware if/else) |
| `Mux(c, a, b)` | Select `a` if `c` else `b` |
| `UIntToOH(x)` | Binary to one-hot (3 becomes `00001000`) |
| `PriorityEncoder(x)` | Index of the lowest set bit |
| `assert(...)`, `printf(...)` | Simulation-only checks and prints |

**Elaboration** is when Scala runs and builds the hardware graph. Ordinary Scala
(`if`, `for`, `val`) runs at elaboration and shapes *what circuit is built*.
Chisel constructs (`when`, `Mux`, `:=`) become *actual hardware*. Mixing them up
is the most common beginner error: `if (x === y)` on hardware values is wrong;
use `when(x === y)`.

The `[L2Replacement] policy=... sets=...` line in the build log is printed at
elaboration, which is why it shows up before any simulation.

## 5. From Chisel to a running simulation

```
Scala/Chisel source
      |  (sbt compiles + runs the generator)   "elaboration"
      v
FIRRTL -> generated Verilog (.sv files under sims/verilator/generated-src)
      |  (Verilator translates to C++, then g++ compiles)
      v
simulator-chipyard.harness-<Config>   (a native executable)
      |  (run with a RISC-V program as argument)
      v
cycle-accurate simulation of the SoC running your benchmark
```

- A Scala error appears during the first step; a Verilator error in the
  second. They look very different in logs.
- **Verilator** turns the Verilog into C++. It is cycle-accurate but much slower
  than real hardware: the simulated chip runs at a few kilohertz of effective
  speed, which is why full benchmarks take a very long time and why the project
  has a `QUICK=1` mode.
- **Rebuild cost:** changing the cache's Scala means a new Verilator build
  (about 15 minutes). Changing only the benchmark C file means no hardware
  rebuild.
- `printf` in Chisel becomes a `$fwrite`-style print in the generated Verilog
  that fires each cycle the condition is true. That is how `L2WINDOW,...` lines
  appear. They only print when the simulator runs with `+verbose`.

## 6. Reading the cache's hardware for this project

The InclusiveCache directory (`Directory.scala`) stores tags and state for every
way of every set. For replacement, the key picture is:

```
request arrives -> directory reads the set's 8 ways
        |-> hit?  -> acceptedHit(set, way)        (update recency state)
        |-> miss? -> victimWayOH(...) picks a way (one-hot, 8 bits)
                     -> new line written -> allocation(set, way)  (insert)
```

Hardware details that affect your policy code:

- **One-hot vs binary.** `victimWayOH` returns a one-hot vector (exactly one bit
  set, one bit per way). Convert with `UIntToOH` / `OHToUInt`. A one-hot assert
  catches a policy that selects zero or several ways.
- **Valid ways first.** If a set has an empty (invalid) way, it should be used
  before evicting anything. The RoundRobin demo shows how.
- **Timing of reads.** `Mem` reads are combinational but the surrounding pipeline
  is registered, so the `readEnable`/`resultValid` arguments tell you which cycle
  the set index is meaningful. Update state only when the matching event fires.
- **State cost.** True LRU for 8 ways needs about 8 x 3 bits per set or a
  pairwise matrix. PLRU needs 7 bits per set. For 1024 sets this is real
  storage, which is why PLRU exists.
- **DIP's PSEL counter** is a saturating counter register with a configurable
  width; leader sets are chosen by comparing bits of the set index at
  elaboration.
- **Read-modify-write conflicts.** If two events touch the same set in one
  cycle, the later write wins. Keep per-set updates to one write per cycle.

## 7. Debug tips

- Add `printf(p"set=$set way=$way\n")` (Chisel's `p` interpolator) to see signals.
  Remember printing requires `+verbose` and slows the simulator.
- Assertion failures abort the simulation with a message; Round Robin uses one.
- Read errors from the **top**: the first `[error]` line is the real cause, later
  ones are fallout.
- Common Chisel errors: "not fully initialized" (a wire has no default), width
  mismatches, and using a Scala `if` on a hardware condition.
- A shorter test is a faster test. Use `QUICK=1` and a single benchmark.

## 8. Glossary

| Term | Meaning |
|---|---|
| RTL | Register-transfer level description of hardware |
| Elaboration | Running the generator to build the hardware graph |
| FIRRTL | Chisel's intermediate representation before Verilog |
| Verilator | Open-source tool compiling Verilog to a C++ simulator |
| Testbench / harness | The wrapper that drives the clock and loads the program |
| One-hot | A vector with exactly one bit set |
| Flip-flop | One bit of register storage |
| SRAM | Dense memory array, used for caches |
| Combinational loop | A circuit whose output feeds back with no register; illegal |
