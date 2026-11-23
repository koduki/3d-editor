#!/usr/bin/env bash
set -euo pipefail

# Prepares the embedded Linux runtime archives packaged into the Android APK:
#   1. debian-arm64-rootfs.tar.gz          (official Debian bookworm-slim arm64)
#   2. openvscode-server-linux-arm64.tar.gz (official Gitpod openvscode-server linux-arm64)

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/android/app/src/main/assets/runtime}"

DEBIAN_IMAGE="${DEBIAN_IMAGE:-debian:bookworm-slim}"
OPENVSCODE_VERSION="${OPENVSCODE_VERSION:-1.109.5}"
OPENVSCODE_TAG="openvscode-server-v${OPENVSCODE_VERSION}"
OPENVSCODE_URL="https://github.com/gitpod-io/openvscode-server/releases/download/${OPENVSCODE_TAG}/${OPENVSCODE_TAG}-linux-arm64.tar.gz"

ROOTFS_OUT="$OUT_DIR/debian-arm64-rootfs.tgz"
VSCODE_OUT="$OUT_DIR/openvscode-server-linux-arm64.tgz"

mkdir -p "$OUT_DIR"

# 1. Debian minimal arm64 rootfs via Docker image export
if [[ ! -f "$ROOTFS_OUT" || "${FORCE_FETCH:-0}" == "1" ]]; then
  echo "[assets] Exporting ${DEBIAN_IMAGE} (linux/arm64) rootfs..."
  docker pull --platform linux/arm64 "$DEBIAN_IMAGE"
  CID="$(docker create --platform linux/arm64 "$DEBIAN_IMAGE" /bin/true)"
  trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT
  TMP_ROOTFS="${ROOTFS_OUT}.tmp"
  docker export "$CID" | gzip -6 > "$TMP_ROOTFS"
  docker rm -f "$CID" >/dev/null 2>&1 || true
  trap - EXIT
  mv "$TMP_ROOTFS" "$ROOTFS_OUT"
else
  echo "[assets] Reusing cached $ROOTFS_OUT"
fi

# 2. Official openvscode-server linux-arm64 release archive
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
ls -lh "$ROOTFS_OUT" "$VSCODE_OUT"
