#!/usr/bin/env bash
# On-device smoke tests: never download or install dependencies automatically.
# Run after installing reviewed ARM64 toolchains. Missing tooling is NOT RUN.
set -uo pipefail
out="$(realpath -m "${1:-$HOME/kucode-self-host-evidence/languages-$(date -u +%Y%m%dT%H%M%SZ)}")"
mkdir -p "$out"
work="$(mktemp -d "$out/work.XXXXXX")"
report="$out/results.tsv"
printf 'test\tresult\tduration_seconds\n' > "$report"
printf 'utc=%s\narchitecture=%s\n' "$(date -u +%FT%TZ)" "$(uname -m)" > "$out/environment.txt"
if [[ "$(uname -m)" != aarch64 && "$(uname -m)" != arm64 ]]; then
  echo "NOT RUN: AURA native ARM64 is required." | tee -a "$out/environment.txt"
  exit 2
fi
failed=0
run() {
  local name="$1" tools_csv="$2" start="$SECONDS"
  shift 2
  local IFS=',' tool
  for tool in $tools_csv; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      printf '%s\tNOT RUN\t0\n' "$name" >> "$report"
      printf 'missing required tool: %s\n' "$tool" > "$out/$name.log"
      failed=1
      return
    fi
  done
  if (cd "$work"; timeout 180 "$@") >"$out/$name.log" 2>&1; then
    printf '%s\tPASS\t%s\n' "$name" "$((SECONDS-start))" >> "$report"
  else
    printf '%s\tFAIL\t%s\n' "$name" "$((SECONDS-start))" >> "$report"; failed=1
  fi
}
cat > "$work/Main.java" <<'JAVA'
class Main { public static void main(String[] a) { if (6*7!=42) throw new AssertionError(); } }
JAVA
cat > "$work/Main.kt" <<'KOTLIN'
fun main() { check(6 * 7 == 42) }
KOTLIN
cat > "$work/check.js" <<'JS'
const assert = require('node:assert/strict');
const fs = require('node:fs');
const cp = require('node:child_process');
assert.equal(cp.execFileSync(process.execPath, ['-p','6*7']).toString().trim(),'42');
fs.writeFileSync('watch.txt','before');
const watcher = fs.watch('watch.txt', () => { clearTimeout(timer); watcher.close(); });
const timer = setTimeout(() => { watcher.close(); process.exitCode = 1; }, 5000);
setTimeout(() => fs.appendFileSync('watch.txt','after'), 100);
JS
cat > "$work/types.ts" <<'TS'
const answer: number = 6 * 7;
if (answer !== 42) throw new Error('wrong result');
TS
cat > "$work/check.py" <<'PY'
import os, pty, subprocess, sqlite3, socket, threading
assert sqlite3.connect(':memory:').execute('select 6*7').fetchone()[0] == 42
master, slave = pty.openpty()
p = subprocess.Popen(['sh','-c','printf ok'], stdout=slave)
os.close(slave); assert os.read(master, 10) == b'ok'; os.close(master); assert p.wait() == 0
server = socket.socket(); server.bind(('127.0.0.1', 0)); server.listen(1)
def serve():
    conn, _ = server.accept()
    with conn: conn.sendall(b'ok')
t=threading.Thread(target=serve); t.start()
with socket.create_connection(server.getsockname(), timeout=3) as client:
    assert client.recv(10)==b'ok'
t.join(); server.close()
PY
cat > "$work/main_test.go" <<'GO'
package smoke
import "testing"
func TestArithmetic(t *testing.T) { if 6*7 != 42 { t.Fatal("wrong result") } }
GO
cat > "$work/check.rb" <<'RUBY'
require 'openssl'
abort unless 6 * 7 == 42 && OpenSSL::Digest::SHA256.hexdigest('test').length == 64
RUBY
mkdir -p "$work/rust/src"
printf '[package]\nname="smoke"\nversion="0.1.0"\nedition="2021"\n' > "$work/rust/Cargo.toml"
printf '#[test] fn arithmetic() { assert_eq!(6*7,42); }\n' > "$work/rust/src/lib.rs"
mkdir -p "$work/cmake"
printf 'cmake_minimum_required(VERSION 3.16)\nproject(smoke C CXX)\nadd_executable(c-check check.c)\nadd_executable(cxx-check check.cpp)\n' > "$work/cmake/CMakeLists.txt"
printf 'int main(void){return 6*7!=42;}\n' > "$work/cmake/check.c"
printf 'int main(){return 6*7!=42;}\n' > "$work/cmake/check.cpp"

run java java,javac bash -c 'java -version && javac Main.java && java Main'
run kotlin java,kotlinc bash -c 'kotlinc -version && kotlinc Main.kt -include-runtime -d check.jar && java -jar check.jar'
run javascript node,npm bash -c 'node --version && npm --version && node check.js'
run typescript node,tsc bash -c 'tsc --version && tsc types.ts && node types.js'
run python python3 bash -c 'python3 --version && (command -v uv >/dev/null 2>&1 && uv --version || echo "uv: not installed") && python3 -m venv venv && venv/bin/python check.py'
run go go bash -c 'go version && GO111MODULE=off go test -v'
run ruby ruby,bundle bash -c 'ruby --version && bundle --version && ruby check.rb'
run rust cargo bash -c 'cargo --version && cd rust && cargo test --offline'
run cpp cmake,cc,c++ bash -c 'cmake --version && cc --version && c++ --version && cmake -S cmake -B build && cmake --build build && ./build/c-check && ./build/cxx-check'
cat "$report"
exit "$failed"
