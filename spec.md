# XR-ACP Companion 仕様書 (spec.md)

- **Version:** 0.3.0
- **Date:** 2026-11-21
- **Status:** Implemented (v0.1 / v0.2 / v0.3 コード実装・自動 E2E 検証完了 / 実機・ストア申請待ち)

---

## 1. プロジェクト概要

### 1.1 目的
XREAL AURA（Android XR / 光学シースルー）および Meta Quest 3（Horizon OS / ビデオパススルー）の空間マルチウィンドウ環境において、ブログ執筆および軽量な Web 開発を快適に行うための **XR 最適化 AI エージェント操作パネル（ACP クライアント）** を構築する。

### 1.2 スコープと役割分担
- **エディタ機能（対象外）:** コード・Markdown 編集、LSP、Git 差分確認は既存の `code-server`（別ウィンドウ・Zen Mode）に完全委譲する。
- **本アプリ（対象）:** バックエンドの ACP（Agent Client Protocol v1）対応 AI エージェント（`agy acp` 等）と通信し、XR 空間での視認性と物理キーボード操作に最適化した「対話・差分レビュー・権限承認・音声入力・履歴復元・オフラインデモ」UI を提供する。

---

## 2. 対象デバイス・動作環境

| 項目 | XREAL AURA (メイン) | Meta Quest 3 (サブ) |
| :--- | :--- | :--- |
| **OS / ブラウザ** | Android XR (Android 14+) / Chrome (TWA) | Meta Horizon OS / Meta Quest Browser (PWABuilder) |
| **光学特性** | OST（光学シースルー）/ 70° FOV / PPD 32 | VST（ビデオパススルー）/ 約 110° FOV |
| **バックエンド実行環境** | 本体 Termux（または `proot-distro` Ubuntu） | 本体 Termux または Tailscale 経由のリモートホスト |
| **入力デバイス** | Bluetooth 物理キーボード（＋トラックボール等） | Bluetooth 物理キーボード（＋ハンドトラッキング） |

---

## 3. システムアーキテクチャ

```text
┌─────────────────────────────┐      ┌─────────────────────────────┐
│  左パネル: XR-ACP Companion │      │  正面パネル: code-server    │
│  (PWA / Chrome / Quest)     │      │  (Zen Mode / 完全黒テーマ)  │
└──────────────┬──────────────┘      └──────────────┬──────────────┘
               │ WebSocket (NDJSON / ACP v1)        │ ファイル監視
               ▼                                    ▼
┌─────────────────────────────┐      ┌─────────────────────────────┐
│  ws-acp-bridge (server.mjs) │      │  共有ワークスペース         │
│  127.0.0.1 / Tailscale IP   │      │  (WORKSPACE_DIR)            │
└──────────────┬──────────────┘      └──────────────▲──────────────┘
               │ stdio (NDJSON)                     │ ファイル読み書き
               ▼                                    │
┌─────────────────────────────┐                     │
│  ACP Agent (agy acp 等)     ├─────────────────────┘
└─────────────────────────────┘
```

- **疎結合パイプ設計:** `ws-acp-bridge` は原則として ACP メッセージの業務内容を解釈せず、WebSocket ⇔ エージェントプロセスの `stdin`/`stdout` を NDJSON（改行区切り JSON-RPC 2.0）として素通しする。
  - **再アタッチ時の最小限ハンドシェイク補助:** プロセス保持中の再接続に限り、すでに完了した `initialize` 応答をキャッシュから返却（クライアントの `id` に合わせてリマップ）し、2回目の `initialize` を拒否するエージェントでもシームレスに `session/resume` へ移行できるようにする。また、切断中にエージェントから届いた未応答の `session/request_permission` を一時バッファし、再接続／`session/resume` 完了後に即時再送する。
- **ファイル同期:** クライアントは `initialize` 時に `fs` / `terminal` ケイパビリティを宣言せず、ファイル読み書き・コマンド実行をエージェントプロセス側へ直接委譲する。エージェントと `code-server` が同一の `WORKSPACE_DIR` を共有することで編集結果をリアルタイム同期する。

---

## 4. 機能要件

### 4.1 バックエンド (`server.mjs`)
- **静的ファイル配信:** 同一 HTTP サーバーからフロントエンド資産（`public/`、PWA アイコン、`privacy.html`、`.well-known/assetlinks.json`）を配信する（パストラバーサル対策・`HEAD` 対応付き）。
- **ヘルスチェック & 設定 API:**
  - `GET /api/health`: 稼働状態・バージョン・認証要否を返却（リモート接続診断用 CORS ヘッダ & `OPTIONS` プリフライト対応）。
  - `GET /api/config`: ワークスペースの絶対パス (`cwd`)、利用可能なエージェント一覧、デフォルトエージェント、認証要否を返却（非ループバック時はトークン認証必須）。
- **WebSocket ⇔ stdio ブリッジ:**
  - 接続 URL: `ws://<host>:<port>/?agent=<agent_key>&token=<secret>`（ヘッダ `Authorization: Bearer <secret>` もサポート）
  - プリセットエージェント: `agy` (`agy acp`), `copilot` (`copilot --acp`), `gemini` (`gemini --acp`), `mock` (`scripts/mock-agent.mjs`)、および環境変数 `ACP_AGENTS_JSON` によるカスタム定義。
- **プロセス保持・セッション復帰:**
  - WebSocket 切断後、一定時間（デフォルト 5 分、環境変数 `KEEPALIVE_MS`）はエージェントプロセスを生存させ、ブラウザのリロードやスリープ復帰時に `session/resume` で即座に再開する。
  - `?reset=1` で保持プロセスをクリーンに再起動できる。
- **リモート接続・認証:**
  - デフォルトは `BIND_HOST=127.0.0.1`、`PORT=8765` バインド（認証不要）。
  - 非ループバック（`0.0.0.0` や Tailscale IP）へバインドする場合は `ACP_BRIDGE_TOKEN` 未設定で起動拒否（フェイルクローズ）し、検証は `crypto.timingSafeEqual` を使用。

### 4.2 フロントエンド (PWA / Vanilla Web)
- **ACP v1 通信クライアント (`public/acp-client.js`):**
  - `initialize` → `session/resume` / `session/load` / `session/new` の自動切替と指数バックオフ再接続。
  - `session/prompt` による送信・割り込み指示（steering）および `session/cancel` による中断。
  - `session/update`（`agent_thought_chunk`, `agent_message_chunk`, `user_message_chunk`, `tool_call`, `tool_call_update`, `plan`, `available_commands_update`, `current_mode_update`, `usage_update`, `notice`）のリアルタイムストリーミング描画。
  - `session/set_mode` による実行モード切替。
- **権限承認 UI (`session/request_permission`):**
  - 複数承認待ちをキューで順序管理し、先行 `tool_call` と `toolCallId` でマージして完全なパス・コマンド・縦型 Unified Diff を表示。
  - 画面下部に固定表示し、キーボードのみ（`y`/`Enter`, `a`, `n`/`Esc`）で完結。
- **接続プロファイル管理 & 診断 (`public/profiles.js` — v0.2):**
  - 「AURA ローカル」「リモート PC (Tailscale / Quest 3)」「オフラインデモ」などの複数プロファイルを CRUD 管理し、ヘッダーからワンクリック切替（プロファイルごとの推奨 OST/VST テーマ自動適用に対応）。
  - 設定モーダルから `/api/health` 往復レイテンシと `/api/config` トークン認証を検証するライブ接続診断機能を搭載。
- **セッション履歴のローカルキャッシュ復元 (`public/history-store.js` — v0.2):**
  - `IndexedDB`（`localStorage` 自動フォールバック）にセッションごとの会話ログ・Unified Diff・プラン・思考プロセスを保存（最大 30 セッション）。
  - `session/resume` 時に過去ログをリプレイしないエージェント（`agy acp` 等）でも、リロード直後にタイムラインを自動復元し、「履歴」モーダルから過去セッションの切替・削除・全消去が可能。
- **オフラインデモモード (`public/demo-client.js` — v0.3):**
  - バックエンド未接続やストア審査環境（`/?demo=1`、または接続失敗バナーからワンクリック切替）でも、ブラウザ内蔵 ACP v1 シミュレータにより縦型 Diff 承認・コマンド承認・Plan モード・モード切替を単体体験可能。
- **光学テーマ切替（OST / VST）・Push-to-Talk 音声入力・ストア準拠 PWA:**
  - `manifest.json`（192/512/maskable PNG アイコン・ショートカット）、Network-First + オフラインシェルプリキャッシュ `sw.js`、`privacy.html`、`.well-known/assetlinks.json`、`store/twa-manifest.json` を完備。

---

## 5. 非機能要件・UX 仕様

### 5.1 光学最適化テーマ仕様

| 項目 | OST モード（XREAL AURA） | VST モード（Meta Quest 3） |
| :--- | :--- | :--- |
| **背景色** | `#000000`（Micro-OLED 発光オフ＝透過） | `rgba(18, 20, 26, 0.88)`（パススルー遮断） |
| **サーフェス色** | `#0a0a0a` / 枠線 `#262626` | `rgba(30, 34, 44, 0.92)` / 枠線 `rgba(255,255,255,0.12)` |
| **本文フォント** | `18px` / `line-height: 1.6` / `font-weight: 500` | `15px` / `line-height: 1.5` / `font-weight: 400` |
| **非アクティブ減光** | `window.blur` 時に全体の輝度を 50% へ自動減光 | 同左（設定で ON/OFF 可） |

### 5.2 入力・エッジケース対策
- **IME 誤送信防止:** `compositionstart` / `compositionend`（＋確定直後 60ms ガード）と `KeyboardEvent.isComposing` および `keyCode === 229` を厳密に判定し、日本語変換確定の `Enter` で送信や承認が暴発しないこと。
- **キーボードショートカット:**
  - `Ctrl + Enter`（または `Cmd + Enter`）: プロンプト送信
  - `Ctrl + Space`: 音声入力の開始 / 停止（トグル）
  - `Esc`（実行中・承認待ちなし）: プロンプト実行中断 (`session/cancel`)
  - 承認待ち時（入力欄非フォーカス時）: `y` / `Enter` で許可、`a` で常時許可、`n` / `Esc` で拒否
- **セルフ開発の軽快さ:** フロントエンドはビルドステップ不要（Vanilla HTML / CSS / ES Modules）とし、Termux 上で編集 → ブラウザリロードのみで即座に反映できること。

---

## 6. 技術スタック・ディレクトリ構成

- **バックエンド:** Node.js v20+（外部依存は `ws` のみ）
- **フロントエンド:** Vanilla HTML5 / CSS3 (CSS Variables) / ES Modules（ビルドツール不要）

```text
xr-acp-companion/
├── spec.md                           # 本仕様書
├── README.md                         # セットアップ・運用ガイド
├── package.json                      # 依存: "ws" のみ
├── server.mjs                        # HTTP 静的配信 + /api/health + /api/config + WS-ACP ブリッジ
├── .github/workflows/ci.yml          # GitHub Actions 自動テスト CI
├── public/
│   ├── index.html                    # アプリケーションシェル
│   ├── manifest.json                 # ストア準拠 PWA マニフェスト
│   ├── sw.js                         # Network-First + オフラインシェル Service Worker
│   ├── privacy.html                  # プライバシーポリシー（日本語/英語）
│   ├── icon.svg                      # ベクターアイコン
│   ├── icons/                        # 192 / 512 / maskable 512 PNG アイコン
│   ├── .well-known/assetlinks.json   # Android TWA Digital Asset Links テンプレート
│   ├── style.css                     # OST/VST テーマ・Unified Diff・フォーカス減光
│   ├── app.js                        # UI 制御・IME 安全入力・承認バー・音声入力
│   ├── acp-client.js                 # ACP v1 JSON-RPC クライアント・再接続管理
│   ├── demo-client.js                # (v0.3) ブラウザ内蔵オフラインデモ ACP クライアント
│   ├── profiles.js                   # (v0.2) 接続プロファイル管理 & Tailscale 診断
│   ├── history-store.js              # (v0.2) IndexedDB/localStorage セッション履歴復元
│   ├── diff.js                       # LCS ベース Unified Diff 計算・描画
│   └── markdown.js                   # XSS 安全な軽量 Markdown レンダラ
├── scripts/
│   ├── mock-agent.mjs                # ACP v1 モックエージェント（動作確認・テスト用）
│   └── watch-ss.sh                   # (v0.2) Termux 用スクショ監視→Markdown リンク自動コピー
├── store/
│   └── twa-manifest.json             # (v0.3) Bubblewrap TWA パッケージ設定テンプレート
├── docs/
│   ├── device-verification.md        # XREAL AURA / Meta Quest 3 実機検証チェックリスト
│   └── store-packaging.md            # Google Play TWA / Meta Horizon Store 手順書
└── test/
    ├── bridge.test.mjs               # ユニット・セキュリティ・ACP ブリッジ統合テスト
    └── ui-e2e.test.mjs               # Headless Chromium CDP ブラウザ E2E テスト
```

---

## 7. マイルストーン進捗

- **v0.1 (MVP):** 実装完了・自動テスト＋Headless Chrome E2E パス（XREAL AURA 実機最終確認のみ残）
- **v0.2 (日常運用強化):** 接続プロファイル管理・ライブ診断・ローカルセッション履歴復元・`watch-ss.sh` 実装＆自動テスト完了（Quest 3 / Termux 実機最終確認のみ残）
- **v0.3 (ストア配布準備):** オフラインデモモード・PNG/Maskable アイコン・`privacy.html`・`assetlinks.json`・`twa-manifest.json`・配布手順書完備（公開ドメイン確定＆ストア提出のみ残）
