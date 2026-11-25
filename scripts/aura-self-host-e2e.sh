#!/usr/bin/env bash
# Automated part of AURA acceptance; no claim that human/physical checks pass.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$(realpath -m "${1:-$HOME/kucode-self-host-evidence/e2e-$(date -u +%Y%m%dT%H%M%SZ)}")"
mkdir -p "$out"
exec > >(tee "$out/e2e.log") 2>&1
echo "Evidence: $out"
echo "UTC: $(date -u +%FT%TZ)"
echo "Host: $(uname -sm)"
git -C "$root" rev-parse HEAD
[[ "$(uname -m)" == aarch64 || "$(uname -m)" == arm64 ]] || { echo "NOT RUN: ARM64 AURA required"; exit 2; }
command -v kucode >/dev/null || { echo "NOT RUN: KuCode CLI missing"; exit 2; }
test -f "$root/runtime-assets.lock.json" || { echo "NOT RUN: reviewed asset lock unavailable"; exit 2; }
kucode sdk doctor | tee "$out/sdk-doctor.json"
python3 "$root/scripts/runtime-assets.py" fetch --lock "$root/runtime-assets.lock.json"
cp "$root/runtime-assets.lock.json" "$out/"
(cd "$root"; node --test tests/*.test.cjs)
(cd "$root/android"; kucode build -- ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=2)
apk="$root/android/app/build/outputs/apk/debug/app-debug.apk"
sha256sum "$apk" > "$out/apk.sha256"
op="$(kucode android install "$apk" dev.koduki.kucode.debug)"
printf '%s\n' "$op" | tee "$out/install-operation.json"
opid="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["operationId"])' <<< "$op")"
echo "Approve the Android system confirmation from the KuCode notification."
while true; do
  status=0
  state="$(kucode android operation-status "$opid")" || status=$?
  printf '%s\n' "$state" | tee -a "$out/install-status.jsonl"
  [[ "$status" == 10 ]] || break
  sleep 2
done
[[ "$status" == 0 ]] || { echo "FAIL: install operation"; exit 1; }
kucode android launch dev.koduki.kucode.debug | tee "$out/launch.json"
echo "Automated build/install/launch passed. Manual physical checks are NOT RUN; see docs/aura-self-host-acceptance.md."
