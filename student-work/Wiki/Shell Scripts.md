# Shell Scripts

A shell script is a text file of commands run by a shell. In this project the scripts (`run-test-suite`, and course scripts under `scripts/`) are **bash**, and they run inside the Linux Docker container. (`run-and-export.ps1` is PowerShell and runs on your Windows host; see section 14.)

## 1. Basics

```bash
#!/usr/bin/env bash        # shebang: which interpreter runs the file
set -euo pipefail          # strict mode (see section 9)

echo "hello"
```

Make it executable and run it:

```bash
chmod +x myscript
./myscript arg1 arg2
bash myscript              # also works without chmod
```

`#` starts a comment. Check syntax without running: `bash -n myscript`. Trace execution: `bash -x myscript`.

## 2. Variables

```bash
name="world"               # NO spaces around =
echo "hello $name"         # double quotes expand variables
echo 'hello $name'         # single quotes are literal
echo "${name}_suffix"      # braces to delimit
readonly PI=3
export MYVAR=1             # visible to child processes
```

Defaults and checks:

| Form | Meaning |
|------|---------|
| `${VAR:-default}` | use default if VAR unset or empty |
| `${VAR:=default}` | also assign it |
| `${VAR:?message}` | abort with message if unset |
| `${#VAR}` | length |
| `${VAR%.c}` / `${VAR#prefix}` | strip suffix / prefix |
| `${VAR/old/new}` | substitute |

Our runner uses the default form so users can override: `CONFIG_LINK="${CONFIG_LINK:-$CHIPYARD_ROOT/...}"`, and `BASELINES`, `POLICIES`, `BENCHMARKS` and `EXPORT_DIR` are set from the environment this way:

```bash
POLICIES="BIP DIP" BENCHMARKS=reuse ./run-test-suite
```

## 3. Quoting (the number one source of bugs)

Always quote variable expansions: `"$file"`. Unquoted values split on whitespace and expand wildcards.

```bash
cp $f dest       # breaks if f="my file.txt"
cp "$f" dest     # correct
```

## 4. Special variables

| Variable | Meaning |
|----------|---------|
| `$0` | script name |
| `$1 ... $9`, `${10}` | positional arguments |
| `$#` | number of arguments |
| `"$@"` | all arguments, each preserved (use this) |
| `$*` | all arguments joined |
| `$?` | exit status of last command |
| `$$` | current process ID |

Finding the script's own directory (used to locate sibling files):

```bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
```

## 5. Exit status and logic

Every command returns 0 (success) or non-zero (failure).

```bash
cmd1 && cmd2        # run cmd2 only if cmd1 succeeded
cmd1 || cmd2        # run cmd2 only if cmd1 failed
cmd1 ; cmd2         # always both
if ! make; then echo "build failed"; fi
exit 1              # leave script with status 1
```

## 6. Conditionals

```bash
if [[ -f "$file" ]]; then
  echo "exists"
elif [[ -d "$file" ]]; then
  echo "directory"
else
  echo "missing"
fi
```

Use `[[ ... ]]` in bash (safer than `[ ... ]`).

| Test | Meaning |
|------|---------|
| `-f f` / `-d f` / `-e f` | regular file / directory / exists |
| `-x f` / `-L f` | executable / symlink |
| `-z s` / `-n s` | string empty / non-empty |
| `a == b`, `a != b` | string compare |
| `a =~ regex` | regex match |
| `n -eq m`, `-ne`, `-lt`, `-le`, `-gt`, `-ge` | integer compare |

`case` for many branches:

```bash
case "$policy" in
  LRU|TrueLRU) echo lru ;;
  BIP)         echo bip ;;
  *)           echo unknown ;;
esac
```

## 7. Loops

```bash
for p in Random PLRU TrueLRU BIP DIP; do
  echo "$p"
done

for f in benchmarks/*.c; do ...; done

while read -r line; do
  echo "$line"
done < file.txt

for ((i = 0; i < 5; i++)); do ...; done
```

`break` and `continue` work as in other languages. This is the shape of our suite: loop over policies, build the simulator, then loop over benchmarks.

Word lists in variables: `for p in $POLICIES; do` intentionally relies on word splitting (unquoted). Arrays are safer for lists with spaces:

```bash
arr=(a b c)
for x in "${arr[@]}"; do echo "$x"; done
echo "${#arr[@]}"         # length
arr+=(d)
```

## 8. Functions

```bash
log() {
  local msg="$1"           # local keeps variables scoped
  echo "[$(date +%T)] $msg"
}
log "starting"
```

Functions return a status with `return N`, and output via `echo`, captured by `$(...)`.

## 9. Strict mode and error handling

`set -euo pipefail`:

- `-e` exit on the first failing command
- `-u` error on unset variables
- `-o pipefail` a pipeline fails if any stage fails

Caveats: in `if cmd; then`, `cmd && x`, `cmd || x`, failures are *tested*, not fatal. When a failure is acceptable (our runner records a failed policy build and moves on), test it explicitly:

```bash
if ! make -C "$SIM_DIR" "CONFIG=$config" > "$build_log" 2>&1; then
  echo "build failed"      # handle, do not abort
fi
```

Cleanup on exit:

```bash
trap 'rm -rf "$tmpdir"' EXIT
tmpdir="$(mktemp -d)"
```

## 10. Command substitution, redirection, pipes

```bash
today="$(date +%m_%d_%H_%M)"      # capture output (prefer $(...) to backticks)

cmd > out.txt          # overwrite stdout
cmd >> out.txt         # append
cmd 2> err.txt         # stderr
cmd > all.txt 2>&1     # both to one file
cmd < in.txt           # stdin
cmd1 | cmd2            # pipe
cmd | tee log.txt      # show and save
cmd > /dev/null        # discard
```

Our runner sends each build and run to a log file, then parses the logs afterward with `parse_run_log.py`.

## 11. Useful tools in scripts

| Tool | Use |
|------|-----|
| `grep -q pattern file` | test for a pattern (exit status only) |
| `grep -E`, `-o`, `-c` | regex, only match, count |
| `sed 's/a/b/g'` | substitute text |
| `awk '{print $2}'` | column processing |
| `cut -d, -f1,3` | CSV columns |
| `sort`, `uniq -c`, `head`, `tail` | sorting and trimming |
| `find . -name '*.scala'` | find files |
| `xargs` | build command lines from input |
| `mkdir -p`, `ln -sf`, `cp`, `rm -f` | filesystem |
| `date +%m_%d_%H_%M` | the timestamp format in our report names |
| `python3 script.py` | anything complex: parse in Python, not shell |

Rule of thumb: shell orchestrates programs; use Python for string/number processing beyond a few lines (hence `parse_run_log.py` and `format_report.py`).

## 12. Arguments and options

```bash
while [[ $# -gt 0 ]]; do
  case "$1" in
    -v|--verbose) VERBOSE=1; shift ;;
    -o) OUT="$2"; shift 2 ;;
    --) shift; break ;;
    *) echo "unknown: $1" >&2; exit 2 ;;
  esac
done
```

Send errors to stderr: `echo "error" >&2`.

## 13. Common pitfalls

- Spaces around `=` in assignments (`x = 1` runs a command named `x`).
- Unquoted variables with spaces.
- Windows line endings (CRLF): a script saved with CRLF fails with `bad interpreter: /usr/bin/env: 'bash\r'` or odd `\r` errors. Fix with `dos2unix file` or `sed -i 's/\r$//' file`. This is a real risk because this repo is edited on Windows; keep LF endings for `.sh` and `run-test-suite`.
- Missing exec bit: `chmod +x`. On Windows mounts it may not stick; use `bash script` instead.
- `cd` in a script affects only that script; use `( cd dir && cmd )` or `make -C dir` to avoid changing your location.
- Variables set inside a pipeline's `while` loop are lost (subshell). Use `done < <(cmd)` instead.
- Each Docker container session starts fresh: environment variables you exported in another shell are gone.
- Comparing integers with `==` inside `[[ ]]` works as a string compare; use `-eq` or `(( a == b ))`.
- `set -e` surprises; test critical commands explicitly.

## 14. PowerShell (the Windows host side)

`run-and-export.ps1` is PowerShell. It runs on Windows and starts Docker Compose with `compose.export.yaml`. Syntax differences:

| bash | PowerShell |
|------|-----------|
| `VAR=1` | `$VAR = 1` |
| `$HOME` | `$env:USERPROFILE` |
| `export X=1` | `$env:X = "1"` |
| `cmd1 && cmd2` | `cmd1; if ($?) { cmd2 }` (Windows PowerShell 5) |
| `[[ -f f ]]` | `Test-Path f` |
| `$(cmd)` | `$(cmd)` |
| `ls`, `cat` | `Get-ChildItem`, `Get-Content` |
| `/path/with/slash` | `C:\path\with\backslash` |

PowerShell passes objects rather than text through pipes. Note that Windows PowerShell 5's `Set-Content -Encoding utf8` adds a BOM, which can break Linux tools reading the file.

## 15. Reading `run-test-suite`

The script follows this order, so when modifying it, find the stage:

1. Strict mode and locating directories.
2. Read settings from the environment (`BASELINES`, `POLICIES`, `BENCHMARKS`, `EXPORT_DIR`).
3. Check the InclusiveCache revision, apply the patch idempotently, symlink the policy stubs and config files.
4. Build the benchmarks with `make -C benchmarks`.
5. For each policy: build the simulator; if elaboration reports "is not implemented", record `unsupported` and continue.
6. For each benchmark: run the simulator, saving a log.
7. Parse logs into `metrics.csv` and `comparison.csv`, then write the formatted report.

Syntax check after any edit: `bash -n run-test-suite`.

## 16. Further reading

- Bash manual: <https://www.gnu.org/software/bash/manual/bash.html>
- ShellCheck (a linter that catches most mistakes above): <https://www.shellcheck.net/>
- Google Shell Style Guide: <https://google.github.io/styleguide/shellguide.html>
- `How Make Files Work.md` for the build tool these scripts invoke.
