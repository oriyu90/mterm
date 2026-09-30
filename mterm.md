# mterm 保守メモ（公開サイトには載せない）

> 保存先ルール: `oriyu90/mterm` の `main` 直下 `mterm.md` に集約（common rules ルール6）。
> 設計正本は `docs/IMPLEMENTATION_PLAN.md`（実装計画書 v1.0 / 2026-09-29、572行）。
> 元 `.docx` は `mterm.docx` に保存。評価報告は `docs/DESIGN_EVALUATION.md`。
> 最終更新: 2026-09-30 / v1.0.0 / 実装者: OpenCode (Muse Spark)

## 1. 署名鍵（更新時はここから持ってくること）

- 一次保管: private リポジトリ `oriyu90/common-rules-document` の `keystores/mterm-upload-key.jks`
- alias: `upload` / STORE_PASSWORD・KEY_PASSWORD 共通: 共通ルール文書 `common rules.md` ルール7 のパスワード（`Youu2911yuki.`）
- 本リポジトリに `.jks` を置かない（`.gitignore` で除外済み）。CI で署名する場合も `RELEASE_KEYSTORE_BASE64` は上記 `.jks` から生成する
- リリースビルド直後に必ず `keystores/sync-keystore.sh mterm [コピー元.jks]` を実行し push 済みにすること（ルール7）
- 今回 v1.0.0: 下記「リリース手順」で鍵を新規生成→ common-rules へ sync 予定。生成後に本節の「未sync」表記を消すこと

## 2. リリース構成

- `apps/app-full`（target 28 / sideload MVP）: `mterm-full-1.0.0.apk` を GitHub Release `v1.0.0` に添付
- `apps/app-modern`（target 36 / experimental）: `mterm-modern-1.0.0.apk` を同 Release に添付（Gate C 未通過の旨を明記）
- `apps/app-remote`（target 36 / Play-compatible）: `mterm-remote-1.0.0.apk` を同 Release に添付（将来 Play 提出用、Debian loader 非含有）
- versionCode: 10000 / versionName: 1.0.0（3 app 共通）。次回は計画書 §14.3 の分離更新に従う（app / rootfs base / apt / AI CLI を混ぜない）
- 紹介サイト正規URL: `https://studio-rizi.pages.dev/projects/mterm/`（4言語 `ja/en/zh/pt`、hreflang・canonical・sitemap は studio-rizi 側で管理）

## 3. 次回更新時のチェックリスト（ここから始めること）

- [ ] R1–R20（計画書 §19）を再確認（Play target API / W^X / 16KB / FGS / SAF / phantom / Debian stable / Node LTS / Claude installer / AGP baseline が変わっていないか）
- [ ] `distribution/manifests/debian-trixie-arm64.json` の `sha256/size/signature/version` を新 rootfs に合わせて更新（Ed25519 署名必須、公開鍵のみアプリ内）
- [ ] Debian stable が 13.7 から進んでいたら `trixie` point release のみ metadata 更新（固定ID `debian-trixie-arm64` は変えない）
- [ ] Node LTS が 24 から変わっていたら preset 表記（README + アプリ内Donate? preset task）を更新。Claude Code installer URL 変更も確認
- [ ] AGP/Kotlin/BOM/NDK の更新は別 branch で互換性CI通過後に行う（Kotlin 2.4.20 は計画書 §6 の通り別 branch）
- [ ] libsu 6.x 採用時は tag pin + `app-remote` の非依存を `NoLoaderCodeTest` で再確認（現 MVP は純粋 su 検出）
- [ ] Termux vendor 範囲が Apache subset から逸脱していないか確認（GPL 混入禁止）
- [ ] `npm run build && npm run validate && npm run count-files` を studio-rizi 側で実行（紹介サイト更新時）
- [ ] Gate A–E（`docs/DESIGN_EVALUATION.md` §6）が全 PASS するまで公開しない。特に Samsung Android 16 の PRoot 性能/OEM 差は必須ゲート

## 4. 既知の制限・実機ゲート残（v1.0.0 時点）

- device runtime 要項目（PTY spawn / PRoot nested exec / apt / vite / Claude E2E / SAF 実機 / FGS 実機）は CI なし。本環境は emulator なしのため `UNVERIFIED` のまま。実機 matrix: API29 / 31 / 33 / 35(16KB) / 36 Pixel / 36 Samsung（必須）/ 37 lane
- NDK は `~/Library/Android/sdk/ndk/28.2.13676358` に手動コピーで復旧した経緯あり（sdkmanager zip エラー）。次回クリーン環境では `sdkmanager --sdk_root=... "ndk;28.2.13676358" "cmake;3.22.1"` を再実行し `source.properties` を確認
- `core/data` は Room 2.7.1（Kotlin 2.2 対応）。2.6.x に戻さないこと
- 全 app テーマは `android:Theme.Material.Light.NoActionBar` 基底（Compose BOM のみでは View 用 Material3 テーマが解決できないため）。`Theme.Material3.*` に戻さないこと
- `core/pty-native` の `externalNativeBuild` と `ndkVersion` をコメントアウトしないこと（ビルドに必須）

## 5. 変更履歴

- 2026-09-30 v1.0.0: 初回実装（P0–P3 + P4基盤）。101 unit tests PASS、assembleDebug 3 APK、16KB 23 .so PASS、日英 parity（full 94 / modern 37 / remote 37）、remote loader 分離 PASS。設計評価は `docs/DESIGN_EVALUATION.md`
