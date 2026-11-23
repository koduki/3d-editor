#!/usr/bin/env bash
set -euo pipefail

# Prepares the embedded Linux runtime archives packaged into the Android APK:
#   1. debian-arm64-rootfs.tgz           (official Debian bookworm-slim arm64 pinned by digest)
#   2. openvscode-server-linux-arm64.tgz (official Gitpod openvscode-server linux-arm64 verified by SHA-256)
#   3. debian-manifest.txt               (exact image digest + installed Debian binary/source packages for GPL/LGPL compliance)

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/android/app/src/main/assets/runtime}"

DEBIAN_IMAGE="${DEBIAN_IMAGE:-debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc}"
OPENVSCODE_VERSION="${OPENVSCODE_VERSION:-1.109.5}"
OPENVSCODE_SHA256="${OPENVSCODE_SHA256:-36d9c14036489b63de84ebace837fcacf7e60e669a0dc715802c5443684ea4dc}"
OPENVSCODE_TAG="openvscode-server-v${OPENVSCODE_VERSION}"
OPENVSCODE_URL="https://github.com/gitpod-io/openvscode-server/releases/download/${OPENVSCODE_TAG}/${OPENVSCODE_TAG}-linux-arm64.tar.gz"

ROOTFS_OUT="$OUT_DIR/debian-arm64-rootfs.tgz"
VSCODE_OUT="$OUT_DIR/openvscode-server-linux-arm64.tgz"
MANIFEST_OUT="$OUT_DIR/debian-manifest.txt"

verify_sha256() {
  local file="$1"
  local expected="$2"
  local actual
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$file" | awk '{print $1}')"
  else
    actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  fi
  if [[ "$actual" != "$expected" ]]; then
    echo "ERROR: SHA-256 mismatch for $file" >&2
    echo "  expected: $expected" >&2
    echo "  actual:   $actual" >&2
    return 1
  fi
  echo "[assets] Verified SHA-256 ($(basename "$file")): $actual"
}

if [[ "$DEBIAN_IMAGE" != *@sha256:* && "${ALLOW_UNPINNED_DEBIAN:-0}" != "1" ]]; then
  echo "ERROR: DEBIAN_IMAGE must be pinned by @sha256 digest (got: $DEBIAN_IMAGE). Set ALLOW_UNPINNED_DEBIAN=1 to override." >&2
  exit 1
fi

mkdir -p "$OUT_DIR"

if [[ "$DEBIAN_IMAGE" == *@sha256:* ]]; then
  DEBIAN_DIGEST="$DEBIAN_IMAGE"
else
  DEBIAN_DIGEST="unknown (generated from cached tarball)"
fi

# 1. Debian minimal arm64 rootfs via Docker image export
if [[ ! -f "$ROOTFS_OUT" || "${FORCE_FETCH:-0}" == "1" ]]; then
  echo "[assets] Exporting ${DEBIAN_IMAGE} (linux/arm64) rootfs..."
  docker pull --platform linux/arm64 "$DEBIAN_IMAGE"
  DEBIAN_DIGEST="$(docker inspect --format='{{index .RepoDigests 0}}' "$DEBIAN_IMAGE" 2>/dev/null || echo "$DEBIAN_DIGEST")"
  CID="$(docker create --platform linux/arm64 "$DEBIAN_IMAGE" /bin/true)"
  TMP_ROOTFS="${ROOTFS_OUT}.tmp"
  trap 'docker rm -f "$CID" >/dev/null 2>&1 || true; rm -f "$TMP_ROOTFS"' EXIT
  docker export "$CID" | gzip -6 > "$TMP_ROOTFS"
  docker rm -f "$CID" >/dev/null 2>&1 || true
  mv "$TMP_ROOTFS" "$ROOTFS_OUT"
  trap - EXIT
  rm -f "$MANIFEST_OUT"
else
  echo "[assets] Reusing cached $ROOTFS_OUT"
fi

# 2. Generate Debian package & source manifest directly from var/lib/dpkg/status inside the rootfs archive
if [[ ! -f "$MANIFEST_OUT" || "${FORCE_FETCH:-0}" == "1" ]]; then
  echo "[assets] Extracting Debian package manifest to $MANIFEST_OUT..."
  python3 - "$ROOTFS_OUT" "$MANIFEST_OUT" "$DEBIAN_IMAGE" "$DEBIAN_DIGEST" <<'PY'
import datetime
import hashlib
import re
import sys
import tarfile

rootfs_path, manifest_path, image_name, digest = sys.argv[1:5]

h = hashlib.sha256()
with open(rootfs_path, "rb") as f:
    for chunk in iter(lambda: f.read(1024 * 1024), b""):
        h.update(chunk)
rootfs_sha256 = h.hexdigest()

status_text = None
with tarfile.open(rootfs_path, "r:gz") as tf:
    for member in tf.getmembers():
        norm = member.name.lstrip("./")
        if norm == "var/lib/dpkg/status":
            extracted = tf.extractfile(member)
            if extracted is not None:
                status_text = extracted.read().decode("utf-8", errors="replace")
            break

if status_text is None:
    raise SystemExit("ERROR: var/lib/dpkg/status not found inside rootfs tarball")

packages = []
for stanza in status_text.strip().split("\n\n"):
    fields = {}
    for line in stanza.splitlines():
        if ":" in line and not line[0].isspace():
            k, v = line.split(":", 1)
            fields[k.strip()] = v.strip()
    if "install ok installed" not in fields.get("Status", ""):
        continue
    pkg = fields.get("Package", "")
    ver = fields.get("Version", "")
    arch = fields.get("Architecture", "")
    src_raw = fields.get("Source", "")
    if src_raw:
        m = re.match(r"^(\S+)(?:\s+\(([^)]+)\))?", src_raw)
        src_pkg = m.group(1) if m else src_raw
        src_ver = m.group(2) if (m and m.group(2)) else ver
    else:
        src_pkg = pkg
        src_ver = ver
    packages.append((pkg, arch, ver, src_pkg, src_ver))

packages.sort(key=lambda x: x[0])

now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
lines = [
    "# KuCode Bundled Debian Package & Corresponding Source Manifest",
    f"# Generated (UTC): {now}",
    f"# Base Image: {image_name}",
    f"# Image Digest: {digest}",
    f"# Rootfs Archive SHA-256: {rootfs_sha256}",
    f"# Installed Packages: {len(packages)}",
    "# Source Archive Reference: https://snapshot.debian.org/package/<source_package>/<source_version>/",
    "#",
    "# binary_package\tarch\tbinary_version\tsource_package\tsource_version",
]
for pkg, arch, ver, src_pkg, src_ver in packages:
    lines.append(f"{pkg}\t{arch}\t{ver}\t{src_pkg}\t{src_ver}")

with open(manifest_path, "w", encoding="utf-8") as out:
    out.write("\n".join(lines) + "\n")

print(f"[assets] Recorded {len(packages)} Debian packages in {manifest_path}")
PY
else
  echo "[assets] Reusing cached $MANIFEST_OUT"
fi

# 3. Official openvscode-server linux-arm64 release archive
if [[ -f "$VSCODE_OUT" && "${FORCE_FETCH:-0}" != "1" ]]; then
  if verify_sha256 "$VSCODE_OUT" "$OPENVSCODE_SHA256"; then
    echo "[assets] Reusing verified cached $VSCODE_OUT"
  else
    echo "[assets] Cached $VSCODE_OUT failed SHA-256 verification; re-downloading..."
    rm -f "$VSCODE_OUT"
  fi
fi

if [[ ! -f "$VSCODE_OUT" || "${FORCE_FETCH:-0}" == "1" ]]; then
  echo "[assets] Downloading ${OPENVSCODE_TAG} (linux-arm64)..."
  TMP_VSCODE="${VSCODE_OUT}.tmp"
  trap 'rm -f "$TMP_VSCODE"' EXIT
  curl -fSL "$OPENVSCODE_URL" -o "$TMP_VSCODE"
  verify_sha256 "$TMP_VSCODE" "$OPENVSCODE_SHA256"
  gzip -t "$TMP_VSCODE"
  mv "$TMP_VSCODE" "$VSCODE_OUT"
  trap - EXIT
fi

echo "[assets] Runtime assets ready:"
ls -lh "$ROOTFS_OUT" "$VSCODE_OUT" "$MANIFEST_OUT"
