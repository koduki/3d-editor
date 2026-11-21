# XR-ACP Companion

XREAL AURA（Android XR / OST 光学シースルー）および Meta Quest 3（Horizon OS / VST ビデオパススルー）の空間マルチウィンドウ環境向けに最適化した、**ACP v1（Agent Client Protocol）クライアント & WebSocket ブリッジ**です。

- 仕様書: [spec.md](./spec.md)
- 実機検証チェックリスト（AURA / Quest 3）: [docs/device-verification.md](./docs/device-verification.md)
- ストアパッケージ化ガイド（TWA / PWABuilder）: [docs/store-packaging.md](./docs/store-packaging.md)

## 主な機能

- **疎結合パイプ設計 (`server.mjs`):** Node.js v20+ と `ws` のみ。WebSocket ⇔ ACP エージェント (`agy acp` 等) の stdio を NDJSON で素通ししつつ、切断後もデフォルト 5 分間プロセスを保持し、未応答の権限承認もバッファして再接続時に自動再送します。
- **ビルド不要の PWA (`public/`):** Vanilla HTML / CSS / ES Modules。Termux 上で編集してブラウザをリロードするだけで即反映されます（オフライン時は Service Worker のシェルキャッシュで起動可能）。
- **光学最適化テーマ (OST / VST):**
  - **OST (XREAL AURA):** `#000000` 背景（Micro-OLED 発光オフ＝透過）・`18px / 1.6 / 500` の高視認性タイポグラフィ。
  - **VST (Meta Quest 3):** 半透明ダークサーフェス・`15px / 1.5 / 400`。
  - **非アクティブ自動減光:** `code-server` 等へフォーカスが移ると輝度を 50% に落とします（設定で切替可）。
- **70° FOV 向け縦型 Unified Diff & キーボード承認:** `session/request_permission` を画面下部に固定表示し、`y` / `Enter`（許可）、`a`（常時許可）、`n` / `Esc`（拒否）で完結します。
- **IME 安全送信 & Push-to-Talk 音声入力:** `Ctrl+Enter` 送信（日本語 IME 変換確定誤爆防止）、`Ctrl+Space` で Web Speech API (`ja-JP`) のトグル入力に対応。
- **接続プロファイル管理 & ライブ診断 (v0.2):** 「AURA ローカル」「リモート PC (Tailscale)」「オフラインデモ」などの複数プロファイルを保存・切替でき、設定画面から `/api/health` レイテンシとトークン認証を診断可能。
- **ローカルセッション履歴復元 (v0.2):** `IndexedDB`（`localStorage` フォールバック）に会話と Diff をキャッシュし、`session/resume` 時にリプレイしないエージェントでもリロード後に直前のタイムラインを即復元。「履歴」モーダルから切替・削除可能。
- **オフラインデモモード & ストア対応資産 (v0.3):** `/?demo=1` またはプロファイル切替でバックエンド無しの ACP v1 デモが動作。192/512/maskable PNG アイコン、`privacy.html`、`.well-known/assetlinks.json`、`store/twa-manifest.json` を同梱。

## クイックスタート

```bash
npm install
```

### 1. XREAL AURA (Termux ローカル起動 / `agy acp`)
```bash
WORKSPACE_DIR=~/workspace npm start
```
ブラウザで `http://127.0.0.1:8765/` を開きます。

### 2. 内蔵モックエージェントまたはオフラインデモで動作確認
```bash
# サーバー側 ACP v1 モックエージェントと接続
npm run start:mock

# ブラウザ内蔵オフラインデモ（サーバー未接続でも動作）
# http://127.0.0.1:8765/?demo=1
```

### 3. Meta Quest 3 から Tailscale 経由でリモート接続
非ループバックバインド時は `ACP_BRIDGE_TOKEN` が必須です（未設定の場合は起動拒否）。

```bash
BIND_HOST=0.0.0.0 \
PORT=8765 \
WORKSPACE_DIR=~/workspace \
ACP_BRIDGE_TOKEN="your-strong-secret-token" \
npm start
```
Meta Quest Browser で `http://<Tailscale-IP>:8765/` を開き、「設定」→「リモート PC (Tailscale / Quest 3)」へトークンを入力して「接続テスト」を実行してください。

## 環境変数

| 変数 | デフォルト | 説明 |
| :--- | :--- | :--- |
| `BIND_HOST` | `127.0.0.1` | バインドアドレス（非ループバック時は `ACP_BRIDGE_TOKEN` 必須） |
| `PORT` | `8765` | HTTP / WebSocket 待ち受けポート |
| `WORKSPACE_DIR` | `process.cwd()` | エージェントと `code-server` が共有するワークスペースの絶対パス |
| `DEFAULT_AGENT` | `agy` | デフォルト起動エージェント (`agy` / `copilot` / `gemini` / `mock`) |
| `KEEPALIVE_MS` | `300000` (5分) | WebSocket 切断後にエージェントプロセスを保持する時間（ms） |
| `ACP_BRIDGE_TOKEN` | `""` | リモート接続用の共有認証トークン |
| `ACP_AGENTS_JSON` | `""` | カスタムエージェント定義 JSON |

## テスト

```bash
# 構文チェック
npm run check

# 全テスト実行（ユニット + ACP ブリッジ E2E + Headless Chromium UI E2E）
npm test
```
