# XR-ACP Workbench 仕様書 (spec.md)

- **Version:** 0.4.0 (Phase 1 Spatial Desktop IDE)
- **Date:** 2026-11-21
- **Status:** Phase 1 実装・Headless Chromium E2E 検証完了 / Phase 2 Android XR ネイティブ殻設計済み

---

## 1. プロジェクト概要

### 1.1 目的
XREAL AURA（Android XR / 光学シースルー）および Meta Quest 3（Horizon OS / ビデオパススルー）などの **空間コンピューター** において、**物理キーボード＋マウス／トラックパッド前提** でブログ執筆・Web 開発を完結できる **空間デスクトップ IDE ＆ ACP v1 AI ワークベンチ（XR-ACP Workbench）** を構築する。

### 1.2 設計原則（スマートフォン・タブレット非想定）
- **デスクトップ × 空間コンピューター専用 UX:** スマホ向けのドロワーや巨大タッチ UI を排し、4 ペイン可変レイアウト（左: ファイルツリー / 中央: タブ式コード＆Markdown エディタ / 下: 軽量コマンドコンソール / 右: ACP AI エージェント）と、ホバー・右クリックコンテキストメニュー・ドラッグリサイズ・キーボードショートカット（`Ctrl+P`, `Ctrl+Shift+P`, `Ctrl+S`, `Ctrl+J`, `Ctrl+L`, `Alt+1..4`）を標準装備する。
- **エージェント主導 × 軽量コンソール:** 重い作業は ACP AI エージェントが担う前提とし、ターミナルは `node-pty` 不要（`child_process.spawn` ベース）の軽量コマンドコンソールとして内蔵する。これにより Android 15 Linux ターミナル（AVF）・Alpine PRoot・リモート Linux/macOS のいずれでもネイティブビルド不要で動作する。
- **Android 15 (API 35) / Android XR ファーストの 2 段階設計:**
  - **Phase 1（本実装）:** Web 空間デスクトップ IDE + ゼロネイティブ依存 Node.js バックエンド（`ws` のみ）。ローカル・Tailscale リモート・オフラインデモすべてで即戦力化。
  - **Phase 2（[docs/android-xr-phase2.md](./docs/android-xr-phase2.md)）:** `window.XRHostBridge` を備えた薄い Android 15 / Android XR ネイティブ殻（ハードウェアショートカット優先捕捉・Jetpack XR `SpatialPanel` 分離・AVF / PRoot ローカル連携）。

---

## 2. 対象デバイス・動作環境

| 項目 | XREAL AURA (メイン) | Meta Quest 3 (サブ) |
| :--- | :--- | :--- |
| **OS / ランタイム** | Android XR (Android 15 / API 35+) | Meta Horizon OS / Meta Quest Browser |
| **光学特性** | OST（光学シースルー）/ 70° FOV / 32 PPD | VST（ビデオパススルー）/ 約 110° FOV |
| **バックエンド実行環境** | Android 15 Linux / PRoot ローカル または Tailscale リモート | Tailscale 経由のリモートホスト（Mac / PC / VPS） |
| **入力デバイス（必須前提）** | Bluetooth 物理キーボード＋マウス／トラックパッド（Puck 含む） | Bluetooth 物理キーボード＋マウス／トラックパッド |

---

## 3. システムアーキテクチャ

```text
┌─────────────────────────────────────────────────────────────────────────┐
│               XR-ACP Workbench (Spatial Desktop IDE / PWA)              │
│  ┌──────────────┐ ┌───────────────────────────┐ ┌────────────────────┐ │
│  │ 左: Explorer │ │ 中央: Tabbed Code/MD Ed.  │ │ 右: ACP AI Agent   │ │
│  │  Git Status  │ │  Syntax HL / MD Preview   │ │  Unified Diff 承認 │ │
│  │  右クリック  │ ├───────────────────────────┤ │  履歴 / 音声入力   │ │
│  │              │ │ 下: Lightweight Console   │ │  コンテキスト添付  │ │
│  └──────┬───────┘ └─────────────┬─────────────┘ └─────────┬──────────┘ │
└─────────┼───────────────────────┼─────────────────────────┼────────────┘
          │ HTTP /api/fs/* + SSE  │ WS /ws/console          │ WS / (ACP v1 NDJSON)
          ▼                       ▼                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│             server.mjs (Node.js v20+ / 外部依存: ws のみ)               │
│  ・Workspace FS Jail + Git API + fs.watch SSE                           │
│  ・ConsoleRunner (child_process.spawn / node-pty 不要)                  │
│  ・ACP stdio Bridge (プロセス保持 5 分 / initialize キャッシュ / 承認再送)│
└─────────────────────────────────┬───────────────────────────────────────┘
                                  ▼
                    WORKSPACE_DIR ＆ ACP Agent (agy acp 等)
```

---

## 4. 機能要件

### 4.1 バックエンド (`server.mjs`, `lib/workspace-fs.mjs`, `lib/console-runner.mjs`)
- **Workspace FS & Git API（すべて `WORKSPACE_DIR` 内に厳密隔離・symlink 脱出防御）:**
  - `GET /api/fs/tree`, `GET /api/fs/file`, `PUT /api/fs/file`, `POST /api/fs/mkdir`, `POST /api/fs/rename`, `DELETE /api/fs/entry`, `GET /api/fs/search`
  - `GET /api/git/status`, `GET /api/git/diff`
  - `GET /api/fs/events` (SSE): `fs.watch` によるリアルタイム変更通知で AI 編集結果をエディタへ自動同期
- **軽量コマンドコンソール (`WS /ws/console`):**
  - `child_process.spawn` ベースでコマンド実行・stdout/stderr ストリーミング・stdin 転送・`SIGINT` (`Ctrl+C`) 中断を提供（`node-pty` 不使用）
- **ACP v1 WebSocket ⇔ stdio ブリッジ (`WS /?agent=...&token=...`):**
  - `agy` / `copilot` / `gemini` / `mock` / `ACP_AGENTS_JSON` 対応
  - 切断後デフォルト 5 分保持、再アタッチ時の `initialize` キャッシュ応答、未応答 `session/request_permission` の再送
- **フェイルクローズ認証:** 非ループバックバインド時は `ACP_BRIDGE_TOKEN` 必須（`crypto.timingSafeEqual`）、全 `/api/*` と全 WS へ適用（`/api/health` のみ疎通診断用に公開）

### 4.2 フロントエンド (`public/` — ビルド不要 Vanilla ES Modules)
- **4 ペイン可変デスクトップレイアウト:**
  - ドラッグリサイズ（左右幅・下コンソール高さを `localStorage` 保存）、ペイン個別トグル、デスクトップステータスバー
- **エクスプローラー (`app.js` / `workspace-client.js`):**
  - ツリー表示・絞り込み・Git バッジ（`M`/`A`/`U`/`D`）・右クリックコンテキストメニュー（開く / AI 添付 / 新規ファイル / 新規フォルダ / 名前変更 / パスコピー / 削除）
- **コード＆Markdown エディタ (`public/editor.js`):**
  - マルチタブ（中クリック閉じ・未保存ドット `●`）、行番号ガター、軽量シンタックスハイライト、Tab/Shift+Tab インデント、`Ctrl+S` 保存、`.md` ライブプレビュー分割、外部変更自動再読込（未保存時は競合バナー表示）、選択範囲・開いているファイルの AI コンテキスト添付
- **クイックオープン & コマンドパレット (`public/palette.js`):**
  - `Ctrl+P`（ファイルファジー検索）、`Ctrl+Shift+P` / `F1`（コマンドパレット）、`Ctrl+Shift+F`（ワークスペース全文検索）
- **軽量コマンドコンソール (`public/console.js`):**
  - ANSI カラー表示、`↑`/`↓` 履歴、`Ctrl+C` 中断、クイックコマンドボタン、出力内 `path:line` クリックによるエディタ行ジャンプ、AI 実行コマンド出力のミラー表示
- **ACP v1 AI パネル (`acp-client.js`, `diff.js`, `markdown.js`, `history-store.js`, `demo-client.js`):**
  - ストリーミング思考・プラン・ツール・縦型 Unified Diff 承認（`y`/`a`/`n`/`Esc`）、IME 誤送信防止、Push-to-Talk 音声入力 (`ja-JP`)、ローカルセッション履歴復元、接続プロファイル＆ライブ診断、オフラインデモ仮想ワークスペース
- **Android 15 / XR ホスト抽象化 (`public/xr-host.js`):**
  - Phase 2 の `window.XRHostBridge` 連携ポイント（ショートカット優先捕捉・`SpatialPanel` 分離 `?focusPane=ai`）

---

## 5. 光学最適化・キーボードショートカット仕様

### 5.1 光学テーマ
- **OST モード（XREAL AURA）:** `#000000` 透過黒背景・高コントラスト非発光シンタックス・`15px` コード / `17px` 対話本文・`window.blur` 時 50% 自動減光
- **VST モード（Meta Quest 3）:** 半透明ダークガラス `rgba(16, 19, 26, 0.90)`・`13.5px` コード / `15px` 対話本文・`window.blur` 時 50% 自動減光

### 5.2 デスクトップショートカット
| ショートカット | 動作 |
| :--- | :--- |
| `Ctrl+P` / `Cmd+P` | クイックファイルオープン |
| `Ctrl+Shift+P` / `F1` | コマンドパレット |
| `Ctrl+Shift+F` | ワークスペース全文検索 |
| `Ctrl+S` | アクティブファイルを保存 |
| `Ctrl+B` | 左ファイルツリー開閉 |
| `Ctrl+J` / `` Ctrl+` `` | 下コンソール開閉＆フォーカス |
| `Ctrl+L` | 右 AI パネルへフォーカス（エディタ選択中は範囲を自動添付） |
| `Alt+1` / `2` / `3` / `4` | Explorer / Editor / AI / Console へ直接フォーカス移動 |
| `Ctrl+Enter` | IME 安全プロンプト送信 |
| `Ctrl+Space` | Push-to-Talk 音声入力 ON / OFF |
| `y` / `a` / `n` / `Esc` | 権限承認バーの許可 / 常時許可 / 拒否（非入力フォーカス時） |
