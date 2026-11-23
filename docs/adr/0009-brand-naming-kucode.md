# ADR-0009: プロダクト名称と識別子を「KuCode」に統一する

- **ステータス:** Accepted
- **日付:** 2026-11-23

## 背景（Context）

空間コンピューティング（Android XR / XREAL AURA 等）向けの単体完結コードエディタとして公開・配布するにあたり、コンセプトを端的に伝え、商標・パッケージ名の衝突を避けられる固有のプロダクト名と一貫した識別子体系を定める必要があった。

## 決定事項（Decision）

1. **名称・読み・表記**
   - **プロダクト名（文中表記）:** `KuCode`
   - **読み:** くうこーど（英: *"koo-code"*）
   - **識別子:** `kucode` / `ku-code`
   - **タグライン:**
     - 日: **空間に、書く。**（説明調: *APK 1 本で完結する、空間コンピューティングのためのコードエディタ。*）
     - 英: **A code editor for spatial computing.**

2. **名前の由来**
   - **空（くう）** に二つの意味を重ねる：
     - **空間** — Android XR の空間デスクトップに浮かぶコードエディタ。
     - **空海** — 日本を代表する能書家。「弘法筆を選ばず」のとおり、端末を選ばず書ける道具でありたいという思い。
   - **Code** — `openvscode-server` 由来のコードエディタとしての系譜を示しつつ、CLI ベース AI コーディングツールと自然に並べて語れる命名とする。

3. **衝突回避と商標ルール（2026-11-23 時点の調査・法的な商標調査ではない）**
   - 調査時点で同名のコードエディタは見当たらない（既存の同表記として韓国の大学開発者コミュニティ `KU-CODE-OSS/KUCODE` やペルーの受託会社 `Ku Code Labs` が存在するが、いずれもエディタ製品ではない）。
   - **単体略称 `ku` / `kuu` を CLI・パッケージ名に使わない**：npm `ku`・`ku-lang`・日本の並列実行 CLI「KUU」との衝突を避けるため、常に `kucode` / `ku-code` を用いる。
   - **名称に「VS Code」「Visual Studio Code」を含めない**：Microsoft Corporation の登録商標との混同を避ける（`THIRD_PARTY_NOTICES.md` の商標免責方針と整合）。

4. **採用する主要識別子**
   - Gradle プロジェクト名: `KuCode`
   - Android `applicationId` / Kotlin パッケージ: `dev.koduki.kucode`
   - Android テーマ: `Theme.KuCode`
   - `DocumentsProvider` クラス / ルート ID: `KuCodeDocumentsProvider` / `kucode_workspace`
   - 管理画面 JS ブリッジ: `KuCodeHost`
   - 内蔵拡張 ID / コマンド・ビュー接頭辞: `kucode-tools` / `kucode.*`
   - ループバック管理 API 環境変数 / ヘッダ: `KUCODE_ADMIN_PORT`, `KUCODE_ADMIN_TOKEN`, `X-KuCode-Token`
   - ゲスト内データ領域 / バージョンマーカー: `/var/lib/kucode-vscode`, `rootfs/.kucode-runtime-version`
   - 通知チャンネル / 停止 Action: `kucode_server` / `dev.koduki.kucode.action.STOP_SERVER`
   - 配布バイナリ名: `kucode-release.apk`, `kucode-release.aab`, `kucode-debug.apk`

## 検討した代替案（Alternatives Considered）

- **代替案 A：機能説明的な仮称のまま継続する**
  - プロダクト固有のアイデンティティや検索性に欠け、将来的な空間コンピューティング＋AI エージェント展開の受け皿になりにくいため不採用。
- **代替案 B：`ku` / `kuu` などの短い単語を CLI・識別子に使う**
  - 既存の OSS ツール・パッケージ（npm `ku`, `ku-lang`, `KUU`）と名前衝突するため不採用。

## 影響とトレードオフ（Consequences）

- **メリット**：表示名・パッケージ ID・拡張 ID・管理 API ヘッダ・配布 APK 名まで一貫したブランド体系となり、利用者にコンセプトが伝わりやすくなった。
- **トレードオフ**：PoC 段階での一括変更であるため、Android `applicationId` の変更により旧 ID からの上書きインストールは別アプリ扱いとなる（データ移行は前提としない）。また本格的な公開配布前には別途法的な商標確認を検討する余地がある。

## 該当実装

- [README.md](../../README.md)
- [android/settings.gradle.kts](../../android/settings.gradle.kts)
- [android/app/build.gradle.kts](../../android/app/build.gradle.kts)
- [android/app/src/main/res/values/strings.xml](../../android/app/src/main/res/values/strings.xml)
- [android/app/src/main/assets/kucode-tools/package.json](../../android/app/src/main/assets/kucode-tools/package.json)
- [.github/workflows/ci.yml](../../.github/workflows/ci.yml)
