# XR-ACP Workbench (Spatial Desktop IDE)

XREAL AURA（Android XR / OST 光学シースルー）および Meta Quest 3（Horizon OS / VST ビデオパススルー）などの **空間コンピューター** 向けに、**物理キーボード＋マウス／トラックパッド前提** で設計した **空間デスクトップ IDE ＆ ACP v1 AI ワークベンチ** です（スマホ・タブレット向けタッチ UI を想定しないデスクトップ設計）。

- 仕様書: [spec.md](./spec.md)
- Phase 2 Android 15 / Android XR ネイティブ化設計: [docs/android-xr-phase2.md](./docs/android-xr-phase2.md)
- 実機検証チェックリスト（AURA / Quest 3）: [docs/device-verification.md](./docs/device-verification.md)
- ストアパッケージ化ガイド（TWA / PWABuilder）: [docs/store-packaging.md](./docs/store-packaging.md)

## 主な特徴

- **4 ペイン可変空間デスクトップ UI（ビルド不要・依存ゼロ Vanilla ES Modules）:**
  - **左:** ファイルツリー（絞り込み・Git バッジ・右クリックコンテキストメニュー）
  - **中央:** タブ式コード＆Markdown エディタ（行番号・シンタックスハイライト・`.md` ライブプレビュー・AI 編集のリアルタイム自動同期・選択範囲の AI 添付）
  - **下:** エージェント連動の **軽量コマンドコンソール**（`node-pty` 不使用・ANSI カラー・`↑`/`↓` 履歴・`Ctrl+C` 中断・クリックで `file:line` ジャンプ）
  - **右:** ACP v1 AI エージェントパネル（ストリーミング思考／プラン・縦型 Unified Diff 承認・IME 安全送信・Push-to-Talk 音声入力・ローカル履歴復元）
- **デスクトップ級キーボード操作:** `Ctrl+P`（クイックオープン）、`Ctrl+Shift+P` / `F1`（コマンドパレット）、`Ctrl+Shift+F`（全文検索）、`Ctrl+S`（保存）、`Ctrl+B`（ツリー）、`Ctrl+J` / `` Ctrl+` ``（コンソール）、`Ctrl+L`（AI 添付＆フォーカス）、`Alt+1..4`（ペイン移動）。
- **ゼロネイティブ依存バックエンド (`server.mjs`):** 外部依存は `ws` のみ。Android 15 Linux（AVF）・Alpine PRoot・リモート Linux/macOS（Tailscale）どこでもビルド不要で動作します。
- **オフラインデモ仮想ワークスペース (`/?demo=1`):** バックエンド未接続でもファイルツリー・エディタ・Markdown プレビュー・コンソール・AI Diff 承認をすべて単体体験できます。

## クイックスタート

```bash
npm install

# モック AI エージェント付きで起動（すぐ全機能を体験）
npm run start:mock

# 本番: agy acp へ接続
WORKSPACE_DIR=~/workspace npm start
```

ブラウザで `http://127.0.0.1:8765/` を開きます（バックエンド無しデモは `http://127.0.0.1:8765/?demo=1`）。

### Tailscale 経由のリモート起動（Meta Quest 3 / AURA リモート）

```bash
BIND_HOST=0.0.0.0 \
PORT=8765 \
WORKSPACE_DIR=~/workspace \
ACP_BRIDGE_TOKEN="your-strong-secret-token" \
npm start
```

## テスト

```bash
npm run check   # 全 JS/MJS 構文チェック
npm test        # ユニット + FS/Git/Console/ACP 統合テスト + Headless Chromium UI E2E
```
