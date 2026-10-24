# How Makefiles Work

`make` is a build tool. You describe *what* files depend on *what*, and `make` rebuilds only what is out of date. Chipyard uses it everywhere: simulator builds (`sim/verilator/Makefile`), the top-level `common.mk`, and our `l2-replacement/benchmarks/Makefile`.

## 1. Core idea: rules

```make
target: prerequisites
	recipe
```

- **target**: the file to produce.
- **prerequisites**: files it is built from. If any is newer than the target (or the target is missing), the recipe runs.
- **recipe**: shell commands. Each line **must start with a TAB**, not spaces. Each line runs in its own shell.

Example:

```make
hello.riscv: hello.c
	riscv64-unknown-elf-gcc hello.c -o hello.riscv
```

Run `make hello.riscv`. Run it again: nothing happens, because it is up to date. Edit `hello.c`: it rebuilds. This timestamp check is why Chipyard rebuilds are incremental (and why touching files can force a rebuild).

Running `make` with no target builds the **first** target in the file (conventionally called `all`).

## 2. Variables

```make
CC := riscv64-unknown-elf-gcc    # set immediately
ARCH ?= rv64imafd                # set only if not already set
CFLAGS += -O2                    # append
```

Use with `$(CC)`. Assignment flavors:

| Operator | Meaning |
|----------|---------|
| `=`  | recursive: expanded each time it is used |
| `:=` | simple: expanded once, at definition |
| `?=` | default only if unset (env or command line wins) |
| `+=` | append |

**Overriding from the command line**, which is how Chipyard selects things:

```
make CONFIG=DIPConfig        # command-line values beat the file's values
CONFIG=DIPConfig make        # environment also works for ?= variables
```

In our benchmarks Makefile, `BENCHES ?= ...` means you can run `make BENCHES="reuse mixed"`, and `OUTDIR` can be redirected the same way.

## 3. Automatic variables and patterns

Inside a recipe:

| Variable | Meaning |
|----------|---------|
| `$@` | the target |
| `$<` | the first prerequisite |
| `$^` | all prerequisites |
| `$(@D)` | directory of the target |

Pattern rule (one rule for many files):

```make
%.o: %.c
	$(CC) $(CFLAGS) -c $< -o $@
```

`%` matches any stem, so `foo.o` is built from `foo.c`.

## 4. Phony targets

Targets that are actions, not files:

```make
.PHONY: all clean list
clean:
	rm -f $(ELFS)
```

Without `.PHONY`, a file named `clean` would make `make clean` think it is already up to date.

## 5. Order-only prerequisites

```make
$(OUTDIR)/x.riscv: x.c | $(OUTDIR)
```

Anything after `|` must exist but does **not** trigger a rebuild when its timestamp changes. Used for output directories.

## 6. Functions

```make
ELFS := $(addprefix $(OUTDIR)/,$(addsuffix .riscv,$(BENCHES)))
```

Common ones: `$(addprefix p,list)`, `$(addsuffix s,list)`, `$(wildcard *.c)`, `$(patsubst %.c,%.o,$(SRCS))`, `$(foreach v,list,text)`, `$(shell cmd)`, `$(if cond,a,b)`.

## 7. Generating rules: `define` / `eval`

Our benchmarks Makefile needs one rule per benchmark, each with a different `-DWORKLOAD=N`:

```make
define BENCH_template
$(OUTDIR)/$(1).riscv: replacement_microbench.c ... | $(OUTDIR)
	$$(CC) $$(CFLAGS) -DWORKLOAD=$(2) $$< -o $$@ $$(LDFLAGS)
endef

$(eval $(call BENCH_template,reuse,1))
```

- `$(1)`, `$(2)` are the template arguments.
- `$(call ...)` substitutes them; `$(eval ...)` parses the result as real Makefile text.
- `$$` is a **double dollar** because the text is expanded twice. `$$@` becomes `$@` at the second pass, so it is the automatic variable and not an empty value.

This is the trickiest part of the file; if a template misbehaves, check the `$$`.

## 8. Reading our benchmarks Makefile

| Line | Purpose |
|------|---------|
| `CC := $(RISCV_PREFIX)gcc` | cross-compiler producing RISC-V code on an x86 host |
| `-march=rv64imafd -mabi=lp64d` | which RISC-V extensions and calling convention to target |
| `-mcmodel=medany` | allows code/data anywhere in the address space |
| `-specs=htif_nano.specs`, `-T htif.ld` | bare-metal runtime and linker script for the Chipyard simulators (HTIF = host-target interface) |
| `-I$(COURSE_BENCH_DIR)` | finds `metrics.h` and `hpm.h` |
| `-static` | no dynamic linking; there is no OS |
| `-DWORKLOAD=N` | selects the benchmark at compile time with `#if` |

## 9. Chipyard-specific usage

- Builds happen in `sim/verilator/` (or `sim/vcs/`):
  - `make CONFIG=<ConfigName>` builds the simulator for that config.
  - `make CONFIG=<ConfigName> run-binary BINARY=<path.riscv>` runs a program.
  - `make CONFIG=<ConfigName> run-binary-hex ...`, `debug` variants produce waveforms.
- Key variables: `CONFIG`, `SUB_PROJECT`, `BINARY`, `LOADMEM`, `EXTRA_SIM_FLAGS`, `VERILATOR_THREADS`, `-j N` (parallel jobs).
- Build outputs land in `sim/verilator/generated-src/` and `output/`. Different configs get separate directories, so building many configs does not clobber each other.
- `common.mk` and `variables.mk` hold the shared logic; they are included with `include`.
- Our `run-test-suite` script calls `make -C "$SIM_DIR" "CONFIG=$config"` for each policy, then runs each benchmark ELF.

## 10. Practical tips

- **`make -n`** dry run: prints commands without running them.
- **`make -j8`** runs independent rules in parallel.
- **`make -B`** forces rebuild of everything.
- **`make VAR=x -p`** or `make --debug=b` to see why something rebuilt.
- "*** missing separator" almost always means spaces instead of a TAB.
- "No rule to make target X" means a prerequisite file does not exist or is misspelled.
- Spaces in paths break `make`; keep paths space-free.
- Environment variable vs. command-line: the command line wins over everything unless the Makefile uses `override`.
- If a Chisel/Scala change does not take effect, the simulator may not have been regenerated; the dependency tracking covers Scala sources, but a stale `generated-src/` can usually be fixed by `make CONFIG=<name> clean` or deleting that config's directory.

## 11. Further reading

- GNU Make manual: <https://www.gnu.org/software/make/manual/make.html>
- Chipyard simulation docs: <https://chipyard.readthedocs.io/en/stable/Simulation/>
