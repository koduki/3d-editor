#!/usr/bin/env bash
# (v0.2) Termux 用スクリーンショット監視スクリプト
# Android のスクリーンショット保存先を監視し、新規画像をブログの assets へコピーして
# Markdown 画像リンクをクリップボードへ自動コピーします。
#
# 必要パッケージ (Termux):
#   pkg install inotify-tools termux-api
#   termux-setup-storage
#
# 使い方 (監視モード):
#   SCREENSHOT_DIR=~/storage/pictures/Screenshots \
#   BLOG_ASSETS_DIR=~/blog/static/images \
#   PUBLIC_PREFIX=/images \
#   ./scripts/watch-ss.sh
#
# 使い方 (単発処理 / テスト用):
#   ./scripts/watch-ss.sh --once /path/to/screenshot.png [--dry-run]

set -euo pipefail

SCREENSHOT_DIR="${SCREENSHOT_DIR:-$HOME/storage/pictures/Screenshots}"
BLOG_ASSETS_DIR="${BLOG_ASSETS_DIR:-$PWD/images}"
PUBLIC_PREFIX="${PUBLIC_PREFIX:-/images}"
ALT_TEXT="${ALT_TEXT:-}"

DRY_RUN=0
ONCE_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --once)
      ONCE_FILE="${2:-}"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      sed -n '2,18p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 2
      ;;
  esac
done

copy_to_clipboard() {
  local text="$1"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    return 0
  fi
  if command -v termux-clipboard-set >/dev/null 2>&1; then
    printf '%s' "$text" | termux-clipboard-set
  elif command -v wl-copy >/dev/null 2>&1; then
    printf '%s' "$text" | wl-copy
  elif command -v xclip >/dev/null 2>&1; then
    printf '%s' "$text" | xclip -selection clipboard
  elif command -v pbcopy >/dev/null 2>&1; then
    printf '%s' "$text" | pbcopy
  fi
}

process_file() {
  local src="$1"
  local filename
  filename="$(basename "$src")"
  local lower="${filename,,}"

  case "$lower" in
    *.png|*.jpg|*.jpeg|*.webp) ;;
    *) return 0 ;;
  esac

  if [[ ! -f "$src" ]]; then
    echo "[watch-ss] Warning: file not found: $src" >&2
    return 1
  fi

  local ts ext dest_name dest_path md_link prefix
  ts="$(date +%Y%m%d-%H%M%S)"
  ext="${lower##*.}"
  dest_name="ss-${ts}.${ext}"
  dest_path="${BLOG_ASSETS_DIR}/${dest_name}"

  # Avoid collisions when multiple screenshots arrive within the same second
  local suffix=1
  while [[ -e "$dest_path" ]]; do
    dest_name="ss-${ts}-${suffix}.${ext}"
    dest_path="${BLOG_ASSETS_DIR}/${dest_name}"
    suffix=$((suffix + 1))
  done

  prefix="${PUBLIC_PREFIX%/}"
  md_link="![${ALT_TEXT}](${prefix}/${dest_name})"

  if [[ "$DRY_RUN" -eq 0 ]]; then
    mkdir -p "$BLOG_ASSETS_DIR"
    cp -- "$src" "$dest_path"
    copy_to_clipboard "$md_link"
  fi

  echo "[watch-ss] Copied: ${dest_name} -> ${md_link}"
}

if [[ -n "$ONCE_FILE" ]]; then
  process_file "$ONCE_FILE"
  exit 0
fi

if ! command -v inotifywait >/dev/null 2>&1; then
  echo "Error: inotifywait が見つかりません。Termux で 'pkg install inotify-tools' を実行してください。" >&2
  exit 1
fi

if [[ ! -d "$SCREENSHOT_DIR" ]]; then
  echo "Error: 監視ディレクトリが存在しません: $SCREENSHOT_DIR" >&2
  echo "Termux の場合は先に 'termux-setup-storage' を実行してください。" >&2
  exit 1
fi

mkdir -p "$BLOG_ASSETS_DIR"
echo "[watch-ss] Watching: $SCREENSHOT_DIR -> $BLOG_ASSETS_DIR (prefix=$PUBLIC_PREFIX)"

inotifywait -m -e close_write,moved_to --format '%f' "$SCREENSHOT_DIR" | while IFS= read -r filename; do
  process_file "${SCREENSHOT_DIR}/${filename}" || true
done
