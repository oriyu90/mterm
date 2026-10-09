# mterm 保守メモ（公開サイトには載せない）

> 保存先ルール: `oriyu90/mterm` の `main` 直下 `mterm.md` に集約（common rules ルール6）。
> 設計正本は `docs/IMPLEMENTATION_PLAN.md`（実装計画書 v1.0 / 2026-09-29、572行）。
> 元 `.docx` は `mterm.docx` に保存。評価報告は `docs/DESIGN_EVALUATION.md`。
> 最終更新: 2026-10-10 / v1.1.1 / 実装者: OpenCode (Muse Spark)

## 1. 署名鍵（更新時はここから持ってくること）

- 一次保管: private リポジトリ `oriyu90/common-rules-document` の `keystores/mterm-upload-key.jks`（push 済み、sync 確認済み）
- alias: `upload` / STORE_PASSWORD・KEY_PASSWORD 共通: 共通ルール文書 `common rules.md` ルール7 のパスワード（`Youu2911yuki.`）
- 本リポジトリに `.jks` を置かない（`.gitignore` で除外済み）。CI で署名する場合も `RELEASE_KEYSTORE_BASE64` は上記 `.jks` から生成する
- リリースビルド直後に必ず `keystores/sync-keystore.sh mterm [コピー元.jks]` を実行し push 済みにすること（ルール7）。v1.0.0 で新規生成→ sync 済み。v1.0.1 では既存鍵を再利用し、新規生成しないこと
- rootfs 署名鍵（Ed25519）: `keystores/mterm-rootfs-ed25519.private.pem`（秘密鍵・非公開）+ `mterm-rootfs-ed25519.public.b64`（公開鍵）。アプリ埋め込みは `core/rootfs-manager` の `RootfsKeys.PUBLIC_KEY_BASE64` のみ。署名は `scripts/sign-rootfs.py --manifest ... --key <private.pem>`

## 2. リリース構成

- `apps/app-full`（target 28 / sideload MVP）: `mterm-full-1.1.1.apk` を GitHub Release `v1.1.1` に添付
- `apps/app-modern`（target 36 / experimental）: `mterm-modern-1.1.1.apk` を同 Release に添付（Gate C 未通過の旨を明記）
- `apps/app-remote`（target 36 / Play-compatible）: `mterm-remote-1.1.1.apk` を同 Release に添付（将来 Play 提出用、Debian loader 非含有）
- versionCode: 10101 / versionName: 1.1.1（3 app 共通）。次回は計画書 §14.3 の分離更新に従う（app / rootfs base / apt / AI CLI を混ぜない）
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

## 4. 既知の制限・実機ゲート残（v1.1.1 時点）

- Android 16 実機（LENOVO TB710FU / API 36 / arm64）で検証済み: v1.0.1 項目に加え、縦横回転維持・landscape 2 ペイン・light/dark/retro テーマ・表示スケール 85〜130%・自動最適化実行・日英 UI。詳細は `docs/ADAPTIVE_UI_PLAN.md` §6 の検証記録（追記予定）
- Android 11 実機（Sony SOV40 / API 30）はパターンロック中のため未検証（次回）。同一コードパス（target 28）のためリスクは低いが、スマホ縦画面の確認が必要

- Android 16 実機（LENOVO TB710FU / API 36 / arm64）で検証済み: Android shell の PTY 起動・プロンプト描画・入力→実行→出力・終了コード・終了フロー・FGS 停止・回転維持・Debian ゲート文・診断実測（PTY/app-data exec/nested exec PASS）・日英 UI。詳細は `docs/DEVICE_ANALYSIS_AND_FIX_PLAN.md`
- Android 11 実機（Sony SOV40 / API 30）はパターンロック中のため未検証（次回）。同一コードパス（target 28）のためリスクは低いが、Gate A 完了には実機確認が必要
- Debian rootfs / proot バイナリの実配布は未実施（ホスティング未定）。コードパス・検証ロジック・署名鍵（Ed25519）は用意済み。未導入端末ではゲート表示
- AndroidBridge UDS サーバ本体と SAF↔mirror 実 I/O 同期は未実装（v1.0.2 以降の候補）。CLI と protocol codec は用意済み

## 5. ビルド環境の注意（引き継ぎ）

- NDK は `~/Library/Android/sdk/ndk/28.2.13676358` に手動コピーで復旧した経緯あり（sdkmanager zip エラー）。次回クリーン環境では `sdkmanager --sdk_root=... "ndk;28.2.13676358" "cmake;3.22.1"` を再実行し `source.properties` を確認
- `core/data` は Room 2.7.1（Kotlin 2.2 対応）。2.6.x に戻さないこと
- 全 app テーマは `android:Theme.Material.Light.NoActionBar` 基底（Compose BOM のみでは View 用 Material3 テーマが解決できないため）。`Theme.Material3.*` に戻さないこと
- `core/pty-native` の `externalNativeBuild` と `ndkVersion` をコメントアウトしないこと（ビルドに必須）
- ターミナル描画は `Text` 行レンダリング（`TerminalView.kt`）。`Canvas#drawText` は初回 0 サイズでクラッシュするため使わないこと。不可視 IME フィールドは `clearAndSetSemantics` + バッファ即時リセット（アクセシビリティ漏洩防止）

## 6. 変更履歴

- 2026-10-10 v1.1.1: スキル適用版。`ui-ux-pro-max` v2.13.0 を `.opencode/skills/` に vendor し、48dp タッチターゲット・8dp 間隔・ブレークポイント別ガターを適用。`AppThemeTest` 追加。129 unit tests PASS。Android 16 実機で 48dp・縦横レトロを検証。SOV40（Android 11・スマホ縦画面）はロック中のため次回
- 2026-10-10 v1.1.0: 適応 UI 版。テーマ 4 種（system/light/dark/retro Win98 風＋ターミナル配色連動）、表示スケール（85〜130%）、縦横・解像度対応（landscape 常時 2 ペイン、terminal 高さは height class 連動、両 pane スクロール化）、自動最適化（`core/diagnostics/AutoTune`＋実機収集＋設定画面適用レポート、診断に network/空き容量行）。125 unit tests PASS（AutoTune/Contrast 含む）、日英 parity（full 130 / modern 37 / remote 37）。Android 16 実機で縦横・3 テーマ・スケール・auto-tune・日英を検証。SOV40（Android 11・スマホ縦画面）はロック中のため次回
- 2026-10-09 v1.0.1: 実機対応版。PTY 配線（`core/pty-runtime` + `core/terminal-session` 新設、`TerminalService` が host 所有、`TerminalView` で描画・入力・リサイズ）、Android shell 実機動作（Android 16 で検証）、診断の実測化（PTY/app-data exec/nested exec probe）、Debian/root の型付きゲート、`localeConfig`（日英 per-app）、rootfs Ed25519 署名鍵の発行・保管・公開鍵埋め込み・署名スクリプト。115 unit tests PASS、日英 parity（full 106 / modern 37 / remote 37）、16KB 再確認
- 2026-09-30 v1.0.0: 初回実装（P0–P3 + P4基盤）。101 unit tests PASS、assembleDebug 3 APK、16KB 23 .so PASS、日英 parity（full 94 / modern 37 / remote 37）、remote loader 分離 PASS。設計評価は `docs/DESIGN_EVALUATION.md`
