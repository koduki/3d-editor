#!/usr/bin/env bash
set -euo pipefail

# Prepares the embedded Linux runtime archives packaged into the Android APK:
#   1. debian-arm64-rootfs.tgz           (official Debian bookworm-slim arm64)
#   2. openvscode-server-linux-arm64.tgz (official Gitpod openvscode-server linux-arm64)
#   3. debian-manifest.txt               (exact image digest + installed Debian binary/source packages for GPL/LGPL compliance)

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/android/app/src/main/assets/runtime}"

DEBIAN_IMAGE="${DEBIAN_IMAGE:-debian:bookworm-slim}"
OPENVSCODE_VERSION="${OPENVSCODE_VERSION:-1.109.5}"
OPENVSCODE_TAG="openvscode-server-v${OPENVSCODE_VERSION}"
OPENVSCODE_URL="https://github.com/gitpod-io/openvscode-server/releases/download/${OPENVSCODE_TAG}/${OPENVSCODE_TAG}-linux-arm64.tar.gz"

ROOTFS_OUT="$OUT_DIR/debian-arm64-rootfs.tgz"
VSCODE_OUT="$OUT_DIR/openvscode-server-linux-arm64.tgz"
MANIFEST_OUT="$OUT_DIR/debian-manifest.txt"

mkdir -p "$OUT_DIR"

DEBIAN_DIGEST="unknown (generated from cached tarball)"

# 1. Debian minimal arm64 rootfs via Docker image export
if [[ ! -f "$ROOTFS_OUT" || "${FORCE_FETCH:-0}" == "1" ]]; then
  echo "[assets] Exporting ${DEBIAN_IMAGE} (linux/arm64) rootfs..."
  docker pull --platform linux/arm64 "$DEBIAN_IMAGE"
  DEBIAN_DIGEST="$(docker inspect --format='{{index .RepoDigests 0}}' "$DEBIAN_IMAGE" 2>/dev/null || echo 'unknown')"
  CID="$(docker create --platform linux/arm64 "$DEBIAN_IMAGE" /bin/true)"
  trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT
  TMP_ROOTFS="${ROOTFS_OUT}.tmp"
  docker export "$CID" | gzip -6 > "$TMP_ROOTFS"
  docker rm -f "$CID" >/dev/null 2>&1 || true
  trap - EXIT
  mv "$TMP_ROOTFS" "$ROOTFS_OUT"
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
    "# XR-Editor Bundled Debian Package & Corresponding Source Manifest",
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
if [[ ! -f "$VSCODE_OUT" || "${FORCE_FETCH:-0}" == "1" ]]; then
  echo "[assets] Downloading ${OPENVSCODE_TAG} (linux-arm64)..."
  TMP_VSCODE="${VSCODE_OUT}.tmp"
  curl -fSL "$OPENVSCODE_URL" -o "$TMP_VSCODE"
  gzip -t "$TMP_VSCODE"
  mv "$TMP_VSCODE" "$VSCODE_OUT"
else
  echo "[assets] Reusing cached $VSCODE_OUT"
fi

echo "[assets] Runtime assets ready:"
ls -lh "$ROOTFS_OUT" "$VSCODE_OUT" "$MANIFEST_OUT"
