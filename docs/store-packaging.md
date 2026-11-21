# ストア配布・パッケージ化ガイド（Google Play TWA / Meta Horizon Store PWABuilder）

このドキュメントは、XR-ACP Companion (v0.3) を **Android XR（Google Play / TWA）** および **Meta Horizon Store（PWABuilder）** へ配布するためのパッケージ化手順と審査チェックリストです（関連イシュー: #9）。

---

## 1. ストア審査対策：オフラインデモモード

ストア審査員はローカルの `ws-acp-bridge` (`server.mjs`) を起動せずにアプリを起動します。XR-ACP Companion は以下の仕組みにより、バックエンド未接続環境でも全コア機能を単体体験できます。

- **デモモード専用起動 URL:** `/?demo=1`（`store/twa-manifest.json` の `startUrl` に設定済み）
- **自動フォールバック導線:** ブリッジ接続に失敗した場合、通知バナーに「オフラインデモで試す」ボタンを表示し、ワンクリックで `DemoAcpClient` へ切り替え可能
- **体験可能な機能:** OST/VST 光学テーマ切替、ストリーミング思考＆プラン、縦型 Unified Diff 権限承認（`y`/`a`/`n`/`Esc`）、コマンド実行承認、モード切替、ローカル履歴キャッシュ復元、Push-to-Talk 音声入力

---

## 2. 静的ホスティング準備（HTTPS 必須）

TWA および PWABuilder ともに HTTPS で公開された PWA オリジンが必要です（例: GitHub Pages / Cloudflare Pages / 独自ドメイン）。

1. `public/` ディレクトリ全体を HTTPS ホストへデプロイします（ビルド不要）。
2. 以下の URL がすべて `200 OK` で返ることを確認します：
   - `https://<your-domain>/`
   - `https://<your-domain>/manifest.json`
   - `https://<your-domain>/sw.js`
   - `https://<your-domain>/privacy.html`
   - `https://<your-domain>/icons/icon-192.png`
   - `https://<your-domain>/icons/icon-512.png`
   - `https://<your-domain>/icons/icon-maskable-512.png`
   - `https://<your-domain>/.well-known/assetlinks.json`

---

## 3. Google Play（Android XR / XREAL AURA）向け TWA パッケージ化

### 3.1 前提ツール
- Node.js v20+
- `@bubblewrap/cli` (`npm i -g @bubblewrap/cli`)
- JDK 17 & Android SDK Command-line Tools

### 3.2 ビルド手順
1. `store/twa-manifest.json` の `host` / `iconUrl` / `maskableIconUrl` / `webManifestUrl` / `fullScopeUrl` / `shortcuts` をデプロイ先ドメインへ書き換えます。
2. `store/` ディレクトリで Bubblewrap ビルドを実行します：
   ```bash
   cd store
   bubblewrap update
   bubblewrap build
   ```
3. 生成された署名鍵の SHA-256 フィンガープリントを取得します：
   ```bash
   keytool -list -v -keystore android.keystore -alias xr-acp-release | grep SHA256
   ```
   （Google Play App Signing を利用する場合は、Play Console の「アプリ署名鍵証明書」の SHA-256 も追記してください）
4. 取得した SHA-256 を `public/.well-known/assetlinks.json` の `sha256_cert_fingerprints` へ反映し、再デプロイします。
5. 生成された `app-release-bundle.aab` を Google Play Console へアップロードします。

---

## 4. Meta Horizon Store（Meta Quest 3）向け PWABuilder パッケージ化

1. [PWABuilder](https://www.pwabuilder.com/) でデプロイ先 HTTPS URL を入力し、Manifest / Service Worker / Security のスコアがすべてグリーンであることを確認します。
2. **Package For Stores → Meta Quest** を選択し、以下を設定して APK を生成します：
   - **Package ID:** `dev.koduki.xracpcompanion`
   - **App Name:** `XR-ACP Companion`
   - **Start URL:** `/?demo=1&theme=vst`（審査時は VST テーマ＋オフラインデモで即体験可能）
   - **Display Mode:** `standalone`
3. Meta Horizon Developer Dashboard から APK をアップロードし、プライバシーポリシー URL に `https://<your-domain>/privacy.html` を登録します。

---

## 5. PWA ストア監査チェックリスト

- [x] `manifest.json` に `id`, `name`, `short_name`, `description`, `start_url`, `display: "standalone"`, `background_color`, `theme_color`, `categories`, `shortcuts` を完備
- [x] `192x192` / `512x512` PNG アイコンおよび `512x512` `maskable` アイコンを同梱
- [x] `sw.js` でオフラインシェルをプリキャッシュし、完全オフラインでもアプリ起動可能
- [x] `privacy.html`（日本語・英語併記、ローカルファースト・ゼロテレメトリ明記）を同梱
- [x] `public/.well-known/assetlinks.json` テンプレートを同梱
- [x] `store/twa-manifest.json` テンプレートを同梱
- [ ] デプロイ先 HTTPS ドメインの確定と `assetlinks.json` への実署名 SHA-256 反映
- [ ] Google Play Console / Meta Horizon Developer Dashboard への署名済みバンドル提出
