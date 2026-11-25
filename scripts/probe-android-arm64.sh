#!/usr/bin/env bash
# Run inside AURA Linux. No ADB, installation or emulation.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$(realpath -m "${1:-$HOME/kucode-self-host-evidence/$(date -u +%Y%m%dT%H%M%SZ)}")"
mkdir -p "$out"
exec > >(tee "$out/probe.log") 2>&1
echo "Evidence: $out"
echo "UTC: $(date -u +%FT%TZ)"
echo "Host: $(uname -sm)"
git -C "$root" rev-parse HEAD || true
[[ "$(uname -m)" == aarch64 || "$(uname -m)" == arm64 ]] || { echo "FAIL: native ARM64 required"; exit 1; }
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk" && -f "$sdk/platforms/android-37/android.jar" ]] || { echo "FAIL: API 37 SDK not found"; exit 1; }
export ANDROID_HOME="$sdk"
tools="$sdk/build-tools/36.0.0"
aapt="${KUCODE_AAPT2:-$tools/aapt2}"
gradle="${KUCODE_GRADLE:-gradle}"
for cmd in java python3 file; do command -v "$cmd" >/dev/null; done
java -version
"$gradle" --version
if command -v android >/dev/null; then android --version || true; fi
for bin in "$aapt" "$tools/zipalign"; do
  [[ -x "$bin" ]] || { echo "FAIL: missing $bin"; exit 1; }
  file -L "$bin"
  python3 - "$bin" <<'PY'
import sys, struct
with open(sys.argv[1], 'rb') as f:
    h = f.read(20)
if len(h) != 20 or h[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H',h,18)[0] != 183:
    raise SystemExit('FAIL: expected native Linux AArch64 ELF')
PY
done
"$aapt" version
"$tools/apksigner" version
fixture="$(mktemp -d "$out/fixture.XXXXXX")"
cp -R "$root/tests/fixtures/android-arm64/." "$fixture/"
"$gradle" -p "$fixture" --no-daemon --max-workers=2 \
  "-Pandroid.aapt2FromMavenOverride=$aapt" \
  :app:testDebugUnitTest :app:assembleDebug --stacktrace
apk="$fixture/app/build/outputs/apk/debug/app-debug.apk"
"$tools/apksigner" verify --verbose "$apk"
"$tools/zipalign" -c -P 16 -v 4 "$apk"
sha256sum "$apk" "$aapt" "$tools/zipalign" > "$out/checksums.txt"
echo "PASS: minimal native ARM64 Android build/signature/alignment; KuCode build and AURA E2E NOT RUN by this script"
