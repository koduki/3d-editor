#!/usr/bin/env bash
# (Phase 2) Android 15 Linux Terminal (AVF / Debian VM) およびリモート Debian/Ubuntu ホスト向け
# XR-ACP Workbench ワンコマンド導入・systemd 自動起動スクリプト
#
# 使い方:
#   WORKSPACE_DIR=~/workspace PORT=8765 ./scripts/setup-android15-linux.sh
#   ./scripts/setup-android15-linux.sh --dry-run

set -euo pipefail

DRY_RUN=0
ENABLE_SYSTEMD=1
WORKSPACE_DIR="${WORKSPACE_DIR:-$HOME/workspace}"
BIND_HOST="${BIND_HOST:-127.0.0.1}"
PORT="${PORT:-8765}"
DEFAULT_AGENT="${DEFAULT_AGENT:-agy}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --no-systemd)
      ENABLE_SYSTEMD=0
      shift
      ;;
    -h|--help)
      sed -n '2,8p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 2
      ;;
  esac
done

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
NODE_BIN="$(command -v node || echo /usr/bin/node)"

run_cmd() {
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "[dry-run] $*"
  else
    "$@"
  fi
}

echo "[setup] App directory: ${APP_DIR}"
echo "[setup] Workspace:     ${WORKSPACE_DIR}"
echo "[setup] Listen:        http://${BIND_HOST}:${PORT} (defaultAgent=${DEFAULT_AGENT})"

if [[ "$DRY_RUN" -eq 0 ]]; then
  if ! command -v node >/dev/null 2>&1; then
    echo "Error: Node.js v20+ が見つかりません。Debian (AVF) では以下を実行してください:" >&2
    echo "  sudo apt-get update && sudo apt-get install -y nodejs npm git" >&2
    exit 1
  fi
  NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
  if [[ "$NODE_MAJOR" -lt 20 ]]; then
    echo "Error: Node.js v20+ が必要です (現在: $(node -v))" >&2
    exit 1
  fi
fi

run_cmd mkdir -p "$WORKSPACE_DIR"
run_cmd npm --prefix "$APP_DIR" install --omit=dev

UNIT_DIR="${HOME}/.config/systemd/user"
UNIT_FILE="${UNIT_DIR}/xr-acp-workbench.service"

UNIT_CONTENT="[Unit]
Description=XR-ACP Workbench Spatial Desktop IDE Backend
After=network-online.target

[Service]
Type=simple
WorkingDirectory=${APP_DIR}
Environment=BIND_HOST=${BIND_HOST}
Environment=PORT=${PORT}
Environment=WORKSPACE_DIR=${WORKSPACE_DIR}
Environment=DEFAULT_AGENT=${DEFAULT_AGENT}
ExecStart=${NODE_BIN} ${APP_DIR}/server.mjs
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target"

if [[ "$ENABLE_SYSTEMD" -eq 1 ]]; then
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "[dry-run] Would write systemd user unit: ${UNIT_FILE}"
    printf '%s\n' "$UNIT_CONTENT"
  elif command -v systemctl >/dev/null 2>&1; then
    mkdir -p "$UNIT_DIR"
    printf '%s\n' "$UNIT_CONTENT" > "$UNIT_FILE"
    systemctl --user daemon-reload || true
    systemctl --user enable --now xr-acp-workbench.service || true
    echo "[setup] systemd user service enabled: xr-acp-workbench.service"
  else
    echo "[setup] systemctl が利用できないため手動起動スクリプトを作成します。"
  fi
fi

LAUNCHER="${HOME}/start-xr-workbench.sh"
if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "[dry-run] Would write launcher script: ${LAUNCHER}"
else
  cat > "$LAUNCHER" <<EOF
#!/usr/bin/env bash
set -euo pipefail
export BIND_HOST="${BIND_HOST}"
export PORT="${PORT}"
export WORKSPACE_DIR="${WORKSPACE_DIR}"
export DEFAULT_AGENT="${DEFAULT_AGENT}"
exec "${NODE_BIN}" "${APP_DIR}/server.mjs"
EOF
  chmod +x "$LAUNCHER"
  echo "[setup] Launcher created: ${LAUNCHER}"
fi

echo "[setup] Done! Android XR アプリを起動すると http://${BIND_HOST}:${PORT}/api/health を自動検出します。"
