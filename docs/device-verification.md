# 実機検証・セットアップガイド（XREAL AURA / Meta Quest 3）

このドキュメントは、自動テストでは代替できない **実機 XR デバイス固有の光学・入力・ネットワーク検証手順** をまとめたチェックリストです（関連イシュー: #6, #7, #8）。

---

## 1. XREAL AURA（Android XR + Termux）セットアップ & 検証

### 1.1 Termux 環境準備
```bash
pkg update && pkg upgrade -y
pkg install -y nodejs git inotify-tools termux-api
termux-setup-storage
```

### 1.2 サーバー起動（ローカル 127.0.0.1）
```bash
git clone https://github.com/koduki/3d-editor.git xr-acp-companion
cd xr-acp-companion
npm install

# agy acp へ接続する場合（デフォルト）
WORKSPACE_DIR=~/workspace PORT=8765 npm start

# agy 未導入で UI のみ先に確認する場合
DEFAULT_AGENT=mock PORT=8765 npm start
```

### 1.3 XREAL AURA 実機チェックリスト
- [ ] **空間マルチウィンドウ配置:** 正面パネルに `code-server`（Zen Mode / 完全黒テーマ）、左パネルに Chrome で `http://127.0.0.1:8765/` を並べて表示できる
- [ ] **PWA インストール:** Chrome メニュー →「ホーム画面に追加」でスタンドアロンウィンドウ（アドレスバー非表示）として起動できる
- [ ] **OST 光学透過確認:** ヘッダーが `OST` のとき背景 `#000000` が Micro-OLED 発光オフで透過し、`18px / line-height 1.6 / weight 500` の文字がにじまず判読できる
- [ ] **非アクティブ自動減光:** 正面の `code-server` へフォーカスを移した際に XR-ACP パネルの輝度が 50% に低下し、戻すと 100% に復帰する
- [ ] **Bluetooth キーボード + 日本語 IME:** 変換確定の `Enter` で誤送信・誤承認が発生せず、`Ctrl+Enter` のみで送信される
- [ ] **70° FOV 縦型 Unified Diff 承認:** 編集提案が縦1列の Unified Diff で表示され、首を振らずに `y` / `a` / `n` / `Esc` で承認・拒否できる
- [ ] **ファイル同期:** 承認後に `code-server` 側へファイル変更が即座に反映される
- [ ] **スリープ復帰 & ローカル履歴復元:** ブラウザ再読込またはデバイススリープ復帰時に `session/resume` とローカルキャッシュから直前の会話ログ・Diff が復元される
- [ ] **Push-to-Talk 音声入力:** `Ctrl+Space` で日本語音声認識 (`ja-JP`) が開始・停止し、テキストが入力欄へ追記される
- [ ] **Termux スクショ監視 (`watch-ss.sh`):**
  ```bash
  SCREENSHOT_DIR=~/storage/pictures/Screenshots \
  BLOG_ASSETS_DIR=~/workspace/static/images \
  PUBLIC_PREFIX=/images \
  ./scripts/watch-ss.sh
  ```
  スクリーンショット撮影時に画像コピーとクリップボードへの Markdown リンクコピーが完了する

---

## 2. Meta Quest 3（Horizon OS + Tailscale リモート接続）セットアップ & 検証

### 2.1 リモートホスト（自宅 PC / サーバー）起動
非ループバックバインド時は `ACP_BRIDGE_TOKEN` の設定が必須です（未設定なら起動時にエラー終了）。

```bash
BIND_HOST=0.0.0.0 \
PORT=8765 \
WORKSPACE_DIR=~/workspace \
ACP_BRIDGE_TOKEN="$(openssl rand -hex 24)" \
npm start
```

> **ヒント:** Tailscale インターフェースのみへ限定したい場合は `BIND_HOST=$(tailscale ip -4)` を指定してください。

### 2.2 Meta Quest 3 実機チェックリスト
- [ ] **プロファイル設定 & 接続テスト:** Meta Quest Browser で `http://<Tailscale-IP>:8765/` を開き、「設定」→「リモート PC (Tailscale / Quest 3)」を選択して WebSocket URL と `ACP_BRIDGE_TOKEN` を入力し、「接続テスト」で **接続 OK（レイテンシ ms 表示）** を確認する
- [ ] **誤トークン検出確認:** わざと誤ったトークンで接続テストを実行し、`401` 認証不一致メッセージと修正ヒントが表示されることを確認する
- [ ] **VST 光学テーマ確認:** プロファイル連動またはヘッダー切替で `VST` モードになり、半透明ダークサーフェス（`rgba(18, 20, 26, 0.88)`）でビデオパススルー背景のノイズを遮断して文字がくっきり読める
- [ ] **Bluetooth キーボード & ハンドトラッキング併用:** 物理キーボードからの `Ctrl+Enter` / `y` / `n` 操作と、ハンドピンチによる承認ボタン・履歴モーダル・設定モーダル操作の双方が快適に行える
- [ ] **プロファイル切替 & オフラインデモ:** 上部プロファイルセレクトから「オフラインデモ」へ即座に切り替え、リモート切断時でも UI 検証できる
