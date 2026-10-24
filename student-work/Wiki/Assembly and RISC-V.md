# Assembly and RISC-V

The benchmarks are C programs compiled to RISC-V machine code and run on the
simulated Rocket core. This page explains enough assembly to read the
simulator's output and to understand how a C array access becomes an L2 access.
See `RTL and Verilog.md` for the hardware side.

## 1. What assembly is

The CPU executes **machine code**: numbered instructions stored in memory.
Assembly is the human-readable text form of those instructions, one line per
instruction. The C compiler (`riscv64-unknown-elf-gcc`) translates C to
assembly, an assembler produces machine code, and the linker produces the
`.riscv` file, which is an ELF executable.

```
C source -> gcc -> assembly -> assembler -> object -> linker -> reuse.riscv
```

## 2. RISC-V names used in this project

| Name | Meaning |
|---|---|
| RISC-V | The open instruction set architecture (ISA) |
| RV64 | 64-bit registers and addresses |
| `rv64imafd` | The `-march` flag: base integer (I), multiply/divide (M), atomics (A), single (F) and double (D) floating point |
| `lp64d` | The `-mabi` flag: C `long`/pointers are 64-bit, doubles in float registers |
| `medany` | The `-mcmodel` flag: code may be placed anywhere in the address space |
| ELF | Executable format: code, data, and an entry point |
| HTIF / fesvr | Host-target interface; lets the program print and exit via the simulator |

The compile line in the Makefile uses all of these: `-march=rv64imafd -mabi=lp64d -mcmodel=medany -specs=htif_nano.specs -T htif.ld`.

## 3. Registers

RISC-V has 32 integer registers, each 64 bits. Names you will see in traces:

| Register | ABI name | Use |
|---|---|---|
| x0 | `zero` | Always 0 |
| x1 | `ra` | Return address |
| x2 | `sp` | Stack pointer |
| x5-x7, x28-x31 | `t0`-`t6` | Temporaries |
| x8, x9, x18-x27 | `s0`-`s11` | Saved across calls |
| x10-x17 | `a0`-`a7` | Arguments and return values |

The simulator's trace line `W[r10=...]` means register `r10` (`a0`) was written.

## 4. Instructions you will see

| Instruction | Meaning |
|---|---|
| `addi a0, a0, 64` | `a0 = a0 + 64` (immediate add) |
| `add a0, a1, a2` | `a0 = a1 + a2` |
| `slli a0, a1, 6` | shift left by 6 (multiply by 64) |
| `ld a0, 0(a1)` | **load** 8 bytes from address `a1 + 0` |
| `sd a0, 8(a1)` | **store** 8 bytes to address `a1 + 8` |
| `lw` / `lb` | load 4 / 1 byte |
| `auipc a0, 0x0` | `a0 = pc + (imm << 12)`; used to build addresses |
| `lui a0, 0x80` | load upper immediate |
| `beq` / `bne` / `blt` | conditional **branch** |
| `jal` / `jalr` | jump (calls and returns) |
| `csrr a0, mcycle` | read the cycle counter into `a0` |
| `csrw` | write a control and status register |

Format: `op destination, source1, source2`. Memory operands are written
`offset(base_register)`.

Example from the first lines of the Round Robin log:
`pc=[10000] inst=[00000517]` is `auipc a0, 0x0`. The program counter (`pc`)
starts at `0x10000`, where the linker script puts the code.

## 5. Why this matters for the L2

Only loads and stores touch memory. Each `ld` goes first to the L1 data cache.
If the line is not there (an **L1 miss**), the request goes to the L2, which is
the cache you are modifying. An L2 miss goes to DRAM.

Take the inner loop of `sweep_lines`:

```c
for (line = 0; line < line_count; ++line)
    sum += read_line(line);          // data[line * 8]
```

It compiles to roughly:

```asm
loop:
    ld    t0, 0(a1)        # load data[line*8]  -> memory access
    add   a0, a0, t0       # sum += value
    addi  a1, a1, 64       # next cache line (8 words * 8 bytes)
    bne   a1, a2, loop     # repeat until the end
```

The stride of 64 bytes means each `ld` touches a new cache line, so the benchmark
generates one cache-line access per iteration without wasting accesses on the
other words in the line. That is why `initialize_data` and `read_line` only use
the first word of each line.

### Address to cache set

With 64-byte lines and 1024 sets (assumed geometry: 512 KiB, 8-way):

```
 63            16 15          6 5      0
 +---------------+-------------+-------+
 |      tag      |  set index  | offset|
 +---------------+-------------+-------+
                    10 bits       6 bits
```

- Offset: bits 5:0 select the byte in the line.
- Set index: bits 15:6 select one of 1024 sets.
- Two addresses 64 KiB (2^16) apart share the same set index. The `conflict`
  benchmark uses a 64 KiB stride to force more than 8 lines into one set.

## 6. Reading a simulator trace line

```
C0:  20 [1] pc=[0000000000010004] W[r10=0000000000010040][1] R[r10=0000000000010000] R[r 0=0] inst=[04050513] DASM(04050513)
```

| Field | Meaning |
|---|---|
| `C0` | Core 0 |
| `20` | Cycle number |
| `[1]` | Instruction is valid and committed |
| `pc=[...]` | Address of the instruction |
| `W[r10=...]` | Register written and the value |
| `R[r10=...]` | Source register values read |
| `inst=[04050513]` | The raw 32-bit machine code (`addi a0, a0, 64`) |

The run logs filter these lines out (`grep -v '^C[0-9]+: '`), because they are huge
and not needed. `+verbose` produces them as a side effect of enabling the cache's
`L2WINDOW` prints.

## 7. Useful commands (inside the container, after `source env.sh`)

```bash
# Disassemble a benchmark, to see what the compiler produced
riscv64-unknown-elf-objdump -d /workspace/output/replacement-benchmarks/reuse.riscv | less

# Only the main function
riscv64-unknown-elf-objdump -d reuse.riscv | sed -n '/<main>:/,/^$/p'

# Emit assembly instead of an executable
riscv64-unknown-elf-gcc -O2 -S -DWORKLOAD=1 -I/workspace/course/benchmarks replacement_microbench.c

# Section sizes (is the data array really 2 MiB?)
riscv64-unknown-elf-size reuse.riscv
```

## 8. Counters and timing

The benchmarks read `mcycle` (cycles elapsed) and the hardware performance
monitor (HPM) counters with `csrr`. `metrics.h` and `hpm.h` wrap this in macros
(`BENCH_BEGIN`, `BENCH_END`). The macros read counters before and after a phase;
the difference is the phase's cost.

- Counter reads are themselves instructions, so they add a few cycles of
  overhead. This is negligible for large phases.
- Core HPM events count L1 effects. The L2 miss numbers come from the cache's own
  `L2AccessMonitor`, not from these counters.

## 9. Quick glossary

| Term | Meaning |
|---|---|
| ISA | The instruction set: the contract between software and hardware |
| PC | Program counter, the address of the next instruction |
| CSR | Control and status register, such as `mcycle` |
| Load / store | Instructions that read / write memory |
| Immediate | A constant encoded inside an instruction |
| Cache line | The 64-byte unit moved between memory levels |
| Stride | Distance in bytes between successive accesses |
| Working set | The set of lines a program actively uses |
| Linker script | `htif.ld`; says where code and data live in memory |
