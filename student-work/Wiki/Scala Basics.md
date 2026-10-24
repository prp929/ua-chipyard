# Scala Basics (for this project)

Chipyard's hardware is written in **Chisel**, which is a library embedded in **Scala**. You do not need all of Scala, but you must read it fluently. This page covers the language first, then the Chisel-specific ideas the L2 replacement work needs.

## 1. Big picture

- Scala runs on the JVM, is statically typed, and mixes object-oriented and functional styles.
- Build tool: **sbt** (invoked by Chipyard's `make`). Scala code is compiled, then **run** to *elaborate* a design: executing the Scala program *produces* a hardware description (FIRRTL, then Verilog).
- Crucial mental model: **Scala code runs at elaboration time and builds hardware. It is not the hardware itself.**

## 2. Values and variables

```scala
val x = 5            // immutable (prefer this)
var y = 5            // mutable
val z: Int = 5       // explicit type; usually inferred
lazy val w = expensive()   // computed on first use
```

## 3. Basic types

`Int`, `Long`, `Boolean`, `Double`, `String`, `Unit` (like void), `Option[T]` (`Some(x)` or `None`), `Seq`/`List`/`Array`, `Map`, tuples `(a, b)`.

## 4. Functions and methods

```scala
def add(a: Int, b: Int): Int = a + b
def log2(n: Int) = ...             // return type inferred
def greet(name: String = "hi")     // default argument
add(b = 2, a = 1)                  // named arguments
```

The last expression is the return value; `return` is rarely used. Functions are values:

```scala
val inc = (n: Int) => n + 1
Seq(1,2,3).map(inc)                // Seq(2,3,4)
Seq(1,2,3).map(_ * 2)              // _ is shorthand for the argument
```

## 5. Control flow (expressions)

```scala
val kind = if (n > 0) "pos" else "neg"      // if is an expression
for (i <- 0 until 8) { ... }                // 0..7
for (i <- 0 to 8)                           // 0..8
val squares = for (i <- 1 to 4) yield i*i
```

`match` is a powerful switch:

```scala
policy match {
  case Random => ...
  case BIP    => ...
  case _      => ...          // default
}
```

## 6. Collections

```scala
val xs = Seq(1, 2, 3)
xs.map(f); xs.filter(p); xs.foreach(f)
xs.zip(ys); xs.zipWithIndex
xs.reduce(_ + _); xs.foldLeft(0)(_ + _)
xs.exists(p); xs.forall(p)
```

Hardware generators use these constantly: "for each way, build a comparator" becomes `ways.map(...)`.

## 7. Classes, objects, traits

```scala
class Counter(val width: Int) {          // constructor params; val makes a field
  def max = (1 << width) - 1
}

object Util {                            // singleton; static-like members
  def ceilLog2(n: Int): Int = ...
}

trait HasStats { def hits: Int }         // interface that may include code
class Foo extends Base with HasStats     // one class, many traits (mixins)
abstract class Policy { def victim(): Int }  // must be implemented by subclass
```

Our policies follow this: `PLRUReplacement`, `TrueLRUReplacement`, `BIPReplacement`, `DIPReplacement` each `extends InclusiveCacheReplacementState` and fill in the required methods.

### Case classes

```scala
case class BIPParams(numerator: Int, denominator: Int)
val p = BIPParams(1, 32)                 // no `new` needed
val q = p.copy(denominator = 64)         // immutable "modify"
```

Case classes get equality, printing, `copy`, and pattern matching for free. Chipyard **parameters** are almost always case classes.

### Sealed traits and enumerations

```scala
sealed trait L2Policy
case object Random extends L2Policy
case object BIP extends L2Policy
```

`sealed` means all subtypes are in one file, so the compiler warns about a non-exhaustive `match`.

## 8. Packages and imports

```scala
package sifive.blocks.inclusivecache    // namespace; matches directory by convention
import chisel3._
import chisel3.util._
import freechips.rocketchip.util._
import scala.math.max
```

`_` imports everything. Files in a package can see each other without importing.

## 9. Option, Either, error handling

```scala
val o: Option[Int] = Some(3)
o.getOrElse(0); o.map(_ + 1); o.isDefined
require(cond, "message")     // throws if false; used for parameter validation
```

`require` is how our parameter checks fail **at elaboration** (e.g. DIP needs `2 * leaderSets < sets`). `???` is a placeholder that throws `NotImplementedError`; our stubs use it.

## 10. Implicits (the confusing part)

Implicit values and parameters are passed automatically by the compiler:

```scala
def foo(x: Int)(implicit p: Parameters) = ...   // p is found in scope
implicit val p: Parameters = ...
```

You will see `(implicit p: Parameters)` on most Rocket Chip classes. Rule of thumb: if a call seems to be missing an argument, it is probably implicit. Also `implicit class` adds methods to existing types, which is why `x.asUInt` or `3.U` works.

## 11. Chisel: Scala that describes hardware

Key rule: **two levels exist.**

| Scala (elaboration time) | Chisel (hardware) |
|--------------------------|-------------------|
| `Int`, `Boolean`, `Seq` | `UInt`, `Bool`, `Vec` |
| `if / else` | `when / .elsewhen / .otherwise` |
| `for` loop | creates repeated hardware |
| `val x = 3` | `val x = 3.U` is a hardware constant |
| runs once while building | describes circuitry that runs every clock |

### Types

```scala
UInt(8.W)           // 8-bit unsigned
Bool()
Vec(8, UInt(3.W))   // array of 8 hardware values
Reg(UInt(10.W))     // flip-flop
RegInit(0.U(10.W))  // flip-flop with reset value
Wire(UInt(4.W))     // combinational value
Mem / SyncReadMem   // memory arrays
```

### Operators

```scala
a + b, a - b, a & b, a | b, a ^ b, ~a
a === b, a =/= b, a < b      // hardware comparison; use === not ==
a(3), a(7, 4)                // bit / bit-slice
Cat(a, b)                    // concatenate
Mux(sel, a, b)               // 2:1 select
```

Common mistake: Scala `==` compares Scala objects, not hardware values. Use `===`.

### Connections

```scala
x := y        // drive x from y (last connection wins)
```

### Conditional hardware

```scala
when (hit) {
  psel := psel + 1.U
}.elsewhen (miss) {
  psel := psel - 1.U
}.otherwise {
  ...
}
```

`when` creates multiplexers; `if` decides what hardware exists at all.

### Useful utilities (`chisel3.util`)

`log2Ceil(n)`, `isPow2(n)`, `PopCount`, `PriorityEncoder`, `OHToUInt`, `UIntToOH`, `LFSR`, `Queue`, `Valid`, `Decoupled` (ready/valid handshake).

### Modules

```scala
class MyModule extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })
  io.out := RegNext(io.in)
}
```

A `Bundle` is a struct of signals; `Input`/`Output` set direction; `RegNext(x)` is x delayed by one cycle.

### Generators

Because Scala runs at build time, a "hardware class" can take parameters and build different hardware:

```scala
class LRU(ways: Int) {
  val ages = Seq.fill(ways)(RegInit(0.U(log2Ceil(ways).W)))
}
```

## 12. Reading our policy stubs

A policy class implements the hooks the patch calls from `Directory`/`MSHR`:

| Hook | When it fires |
|------|---------------|
| `acceptedHit` | a lookup hit a way, so update recency |
| `allocation` | a fill inserts a new line, so choose its initial position (BIP lives here) |
| `invalidation` | a line is removed, so clear its state |
| `victimWayOH` | an eviction is needed: return the chosen way as a **one-hot** vector |

Typical translation of ideas to Chisel:

- *Per-set recency state*: a `Mem` or `Vec` of registers indexed by set.
- *BIP's 1/32 coin flip*: an `LFSR` compared against a threshold.
- *DIP's PSEL*: a saturating `RegInit` counter updated with `when` on leader-set misses.
- *Leader-set check*: a function of the set index (e.g. low bits compared with a constant), computed with ordinary Chisel comparisons.

## 13. Config and parameter idioms

```scala
class MyConfig extends Config(
  new WithMyFeature ++
  new BaseConfig)
```

`++` composes configs left-to-right with the left taking priority. `WithX` classes use `new Config((site, here, up) => { case SomeKey => ... })`, a **partial function** (a `case` block) that answers "what is the value for this key?". `site` is the final merged view, `here` this config, `up` the next config below. See `How Chipyard Works.md` §6.

## 14. Common errors

| Symptom | Usual cause |
|---------|-------------|
| `type mismatch: found Int, required UInt` | forgot `.U` |
| `Wire not fully initialized` | some path through `when` does not drive it; give a default first |
| `Reference ... is not fully initialized` | same family |
| `not found: value x` | missing `import` or wrong package |
| `could not find implicit value for Parameters` | the surrounding scope lacks `implicit p` |
| `Bool` vs `Boolean` confusion | `Boolean` is Scala, `Bool` is hardware |
| `if (hwBool)` rejected | use `when`, or `Mux` |
| Combinational loop | a signal depends on itself without a register |

## 15. Debugging tips

- Scala `println` runs at elaboration only; it prints once, not every cycle.
- Hardware `printf(p"x=${x}\n")` prints during simulation. Our `L2AccessMonitor` uses it.
- `require`/`assert` in Scala check elaboration; Chisel `assert(cond)` checks at simulation.
- Compile only: sbt compile via Chipyard's build; read the first error, later ones are often cascade.

## 16. Further reading

- *Scala Book*: <https://docs.scala-lang.org/scala3/book/introduction.html>
- Chisel Bootcamp (interactive): <https://github.com/freechipsproject/chisel-bootcamp>
- Chisel cheat sheet and docs: <https://www.chisel-lang.org/docs>
- `How Chipyard Works.md` for repo layout and config concepts.
