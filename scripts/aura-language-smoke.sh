#!/usr/bin/env bash
# On-device smoke tests: never download or install dependencies automatically.
# Run after installing reviewed ARM64 toolchains. Missing tooling is NOT RUN.
set -uo pipefail

allow_non_aura=0
with_network=0
out_arg=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --allow-non-aura|--ci-self-test)
      allow_non_aura=1
      shift
      ;;
    --with-network)
      with_network=1
      shift
      ;;
    --output)
      if [[ $# -lt 2 || -z "${2:-}" ]]; then
        echo "Error: --output requires a directory argument" >&2
        exit 2
      fi
      out_arg="$2"
      shift 2
      ;;
    --output=*)
      out_arg="${1#--output=}"
      if [[ -z "$out_arg" ]]; then
        echo "Error: --output= requires a non-empty directory" >&2
        exit 2
      fi
      shift
      ;;
    --*)
      echo "Unknown option: $1" >&2
      exit 2
      ;;
    -*)
      echo "Unknown option: $1" >&2
      exit 2
      ;;
    *)
      if [[ -z "$out_arg" ]]; then
        out_arg="$1"
        shift
      else
        echo "Unexpected positional argument: $1" >&2
        exit 2
      fi
      ;;
  esac
done

raw_out="${out_arg:-$HOME/kucode-self-host-evidence/languages-$(date -u +%Y%m%dT%H%M%SZ)}"
if command -v realpath >/dev/null 2>&1; then
  out="$(realpath -m "$raw_out")"
else
  out="$raw_out"
fi

arch="$(uname -m)"
utc_now="$(date -u +%FT%TZ)"

if [[ "$arch" == "aarch64" || "$arch" == "arm64" ]]; then
  execution_mode="AURA"
elif [[ "$allow_non_aura" == "1" ]]; then
  execution_mode="CI/host"
else
  mkdir -p "$out"
  printf 'utc=%s\narchitecture=%s\n' "$utc_now" "$arch" > "$out/environment.txt"
  echo "NOT RUN: AURA native ARM64 is required." | tee -a "$out/environment.txt"
  exit 2
fi

if [[ "$with_network" == "1" || "${KUCODE_SMOKE_NETWORK:-0}" == "1" ]]; then
  network_mode="with-network"
else
  network_mode="offline"
fi
network_url="${KUCODE_SMOKE_NETWORK_URL:-https://deb.debian.org/}"

mkdir -p "$out"
work="$(mktemp -d "$out/work.XXXXXX")"
trap 'rm -rf "$work"' EXIT

report="$out/results.tsv"
summary_file="$out/summary.json"
printf 'test\tresult\tduration_seconds\tversion\texecution_mode\n' > "$report"
printf 'utc=%s\narchitecture=%s\nexecution_mode=%s\nnetwork_mode=%s\n' \
  "$utc_now" "$arch" "$execution_mode" "$network_mode" > "$out/environment.txt"

failed=0
pass_count=0
fail_count=0
not_run_count=0
total_count=0
json_results=""

sanitize_version() {
  local raw="${1:-}"
  local cleaned
  cleaned="$(printf '%s' "$raw" | tr '\r\n\t' '   ' | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//')"
  if [[ -z "$cleaned" ]]; then
    printf 'unknown'
  else
    printf '%s' "${cleaned:0:160}"
  fi
}

json_escape() {
  local s="${1:-}"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\t'/\\t}"
  s="${s//$'\r'/\\r}"
  s="${s//$'\n'/\\n}"
  printf '"%s"' "$s"
}

record_result() {
  local name="$1" res_status="$2" dur="$3" ver="$4" log_file="$5"
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$res_status" "$dur" "$ver" "$execution_mode" >> "$report"
  total_count=$((total_count + 1))
  case "$res_status" in
    PASS) pass_count=$((pass_count + 1)) ;;
    FAIL) fail_count=$((fail_count + 1)); failed=1 ;;
    "NOT RUN") not_run_count=$((not_run_count + 1)); failed=1 ;;
  esac
  local entry
  entry=$(printf '{"test":%s,"result":%s,"durationSeconds":%s,"version":%s,"executionMode":%s,"logFile":%s}' \
    "$(json_escape "$name")" \
    "$(json_escape "$res_status")" \
    "$dur" \
    "$(json_escape "$ver")" \
    "$(json_escape "$execution_mode")" \
    "$(json_escape "$log_file")")
  if [[ -n "$json_results" ]]; then
    json_results="${json_results},${entry}"
  else
    json_results="${entry}"
  fi
}

run() {
  local name="$1" tools_csv="$2" ver_cmd="$3" start="$SECONDS"
  shift 3
  local IFS=',' tool
  for tool in $tools_csv; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      printf 'missing required tool: %s\n' "$tool" > "$out/$name.log"
      record_result "$name" "NOT RUN" 0 "not-installed" "$name.log"
      return
    fi
  done
  local raw_ver
  raw_ver="$(cd "$work" && bash -c "$ver_cmd" 2>&1 || true)"
  local ver
  ver="$(sanitize_version "$raw_ver")"
  if (cd "$work"; timeout 180 "$@") >"$out/$name.log" 2>&1; then
    record_result "$name" "PASS" "$((SECONDS-start))" "$ver" "$name.log"
  else
    record_result "$name" "FAIL" "$((SECONDS-start))" "$ver" "$name.log"
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

run java java,javac "java -version 2>&1 | head -n 1" bash -c 'java -version && javac Main.java && java Main'
run kotlin java,kotlinc "kotlinc -version 2>&1 | head -n 1" bash -c 'kotlinc -version && kotlinc Main.kt -include-runtime -d check.jar && java -jar check.jar'
run javascript node,npm "node --version 2>&1 | head -n 1" bash -c 'node --version && npm --version && node check.js'
run typescript node,tsc "tsc --version 2>&1 | head -n 1" bash -c 'tsc --version && tsc types.ts && node types.js'
run python python3 "python3 --version 2>&1 | head -n 1" bash -c 'python3 --version && (command -v uv >/dev/null 2>&1 && uv --version || echo "uv: not installed") && python3 -m venv venv && venv/bin/python check.py'
run go go "go version 2>&1 | head -n 1" bash -c 'go version && GO111MODULE=off go test -v'
run ruby ruby,bundle "ruby --version 2>&1 | head -n 1" bash -c 'ruby --version && bundle --version && ruby check.rb'
run rust cargo "cargo --version 2>&1 | head -n 1" bash -c 'cargo --version && cd rust && cargo test --offline'
run cpp cmake,cc,c++ "cmake --version 2>&1 | head -n 1" bash -c 'cmake --version && cc --version && c++ --version && cmake -S cmake -B build && cmake --build build && ./build/c-check && ./build/cxx-check'

if [[ "$network_mode" == "with-network" ]]; then
  export KUCODE_SMOKE_NETWORK_URL_EFF="$network_url"
  if command -v curl >/dev/null 2>&1; then
    run network curl "curl --version 2>&1 | head -n 1" curl -fsSL --max-time 10 "$network_url" -o /dev/null
  elif command -v python3 >/dev/null 2>&1; then
    run network python3 "python3 --version 2>&1 | head -n 1" python3 -c 'import os, urllib.request; r = urllib.request.urlopen(os.environ["KUCODE_SMOKE_NETWORK_URL_EFF"], timeout=10); assert 200 <= r.status < 400'
  elif command -v node >/dev/null 2>&1; then
    run network node "node --version 2>&1 | head -n 1" node -e 'fetch(process.env.KUCODE_SMOKE_NETWORK_URL_EFF, {signal: AbortSignal.timeout(10000)}).then(r => { if (!r.ok) process.exit(1); })'
  else
    printf 'missing required tool: curl/python3/node\n' > "$out/network.log"
    record_result "network" "NOT RUN" 0 "not-installed" "network.log"
  fi
fi

printf '{\n  "schema": 1,\n  "utc": %s,\n  "architecture": %s,\n  "executionMode": %s,\n  "networkMode": %s,\n  "exitCode": %s,\n  "counts": {"pass": %s, "fail": %s, "notRun": %s, "total": %s},\n  "results": [%s]\n}\n' \
  "$(json_escape "$utc_now")" \
  "$(json_escape "$arch")" \
  "$(json_escape "$execution_mode")" \
  "$(json_escape "$network_mode")" \
  "$failed" \
  "$pass_count" \
  "$fail_count" \
  "$not_run_count" \
  "$total_count" \
  "$json_results" > "$summary_file"

cat "$report"
exit "$failed"
