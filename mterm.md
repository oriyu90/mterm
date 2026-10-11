# aTerm 保守メモ（公開サイトには載せない）

> 保存先ルール: `oriyu90/mterm` の `main` 直下 `mterm.md` に集約（common rules ルール6）。
> 設計正本は `docs/IMPLEMENTATION_PLAN.md`（実装計画書 v1.0 / 2026-09-29、572行）。
> 元 `.docx` は `mterm.docx` に保存。評価報告は `docs/DESIGN_EVALUATION.md`。
> 最終更新: 2026-10-11 / v1.3.0 / 実装者: OpenCode (Muse Spark)

## 1. 署名鍵（更新時はここから持ってくること）

- 一次保管: private リポジトリ `oriyu90/common-rules-document` の `keystores/mterm-upload-key.jks`（push 済み、sync 確認済み）
- alias: `upload` / STORE_PASSWORD・KEY_PASSWORD 共通: 共通ルール文書 `common rules.md` ルール7 のパスワード（`Youu2911yuki.`）
- 本リポジトリに `.jks` を置かない（`.gitignore` で除外済み）。CI で署名する場合も `RELEASE_KEYSTORE_BASE64` は上記 `.jks` から生成する
- リリースビルド直後に必ず `keystores/sync-keystore.sh mterm [コピー元.jks]` を実行し push 済みにすること（ルール7）。v1.0.0 で新規生成→ sync 済み。v1.0.1 では既存鍵を再利用し、新規生成しないこと
- rootfs 署名鍵（Ed25519）: `keystores/mterm-rootfs-ed25519.private.pem`（秘密鍵・非公開）+ `mterm-rootfs-ed25519.public.b64`（公開鍵）。アプリ埋め込みは `core/rootfs-manager` の `RootfsKeys.PUBLIC_KEY_BASE64` のみ。署名は `scripts/sign-rootfs.py --manifest ... --key <private.pem>`

## 2. リリース構成

- `apps/app-full`（target 28 / sideload MVP）: `mterm-full-1.3.0.apk` を GitHub Release `v1.3.0` に添付
- `apps/app-modern`（target 36 / experimental）: `mterm-modern-1.3.0.apk` を同 Release に添付（Gate C 未通過の旨を明記）
- `apps/app-remote`（target 36 / Play-compatible）: `mterm-remote-1.3.0.apk` を同 Release に添付（将来 Play 提出用、Debian loader 非含有）
- versionCode: 10300 / versionName: 1.3.0（3 app 共通）。rootfs は `rootfs-13.7-r1` Release を継続使用（app と rootfs base の分離更新、計画書 §14.3）
- v1.3.0 APK SHA-256（`apksigner verify` 済み、cert `57:36:82:84:…:00:21` v2 scheme）:
  - full: `c76d2dd0a6016d19d03370e79553fa779eeeebedf310bf2a83b232c24230676b`
  - modern: `b6cf8c5d22a3dddad869c4134ef54a3808e854405431ec180b06aa31c453328d`
  - remote: `96a5c80eed86a0a7f09dbc165e48d81d4cf26f9c08fd09ac85cda1ba53c7d5e7`
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

## 4. 既知の制限・実機ゲート残（v1.3.0 時点）

- v1.3.0 release smoke（署名済み APK の実機起動確認）は USB 切断中のため未実施。再接続時に実施すること（R8 済みビルドのため起動時クラッシュの目視が必要）
- Mac（10.127.37.43）とデバイス（192.168.0.177）は別サブネットで相互不通。ゲスト http.server→LAN IP の HTTP 200 は確認済み（機構 OK）。同一 LAN への join 後に Mac 側から対向確認すること
- ssh-ed25519 ホスト鍵は非対応（SpongyCastle 1.58 の制限、clean failure）。RSA/ECDSA のみ
- ゲスト外向き TCP 不通（Android 層・loopback は OK）。proot 経由の制約として要調査
- SOV40（Android 11）は adb 未接続のため v1.3.0 未検証。Debian/SSH の API 30 動作は次回

- Android 16 実機（LENOVO TB710FU / API 36 / arm64）で検証済み: Debian 13.7-r1 導入（署名検証つき）・対話 zsh セッション（入力→実行→出力）・APT 通信・Python 3.13.5・Node 24.21.0/npm 11.19.0・Claude Code 2.1.296・OpenCode 0.0.55・localhost http.server 到達（200）。詳細は `docs/CLI_FEASIBILITY_AND_FIX_PLAN.md`（P1–P12）
- Android 11 実機（Sony SOV40 / API 30）で検証済み: Debian READY・対話 zsh（`echo` 実行確認）・Node プリセット（v24.21.0/npm 11.19.0、exit=0）。Ed25519 非対応のため RSA-2048 併用署名で検証（`ManifestSignaturePolicyTest`）
- 対話入力は `KeyboardType.Password`＋`ImeAction.Go`＋ミラー差分方式（P12）。日本語 IME の全角変換・貼り付け重複を実機で潰した。かな入力自体は可能（表示のみ、全角 round-trip テストあり）
- v2.0 で実装済みのため上記「未実装」候補は解消: Bridge UDS サーバ本体・SAF↔mirror 実同期・他アプリ VIEW 受付・SSH・検索/分割・電池導線（詳細は `docs/COMPLETE_TERMINAL_PLAN_V2.md`、`docs/AUDIT_ROUND3.md`）

## 5. ビルド環境の注意（引き継ぎ）

- NDK は `~/Library/Android/sdk/ndk/28.2.13676358` に手動コピーで復旧した経緯あり（sdkmanager zip エラー）。次回クリーン環境では `sdkmanager --sdk_root=... "ndk;28.2.13676358" "cmake;3.22.1"` を再実行し `source.properties` を確認
- `core/data` は Room 2.7.1（Kotlin 2.2 対応）。2.6.x に戻さないこと
- 全 app テーマは `android:Theme.Material.Light.NoActionBar` 基底（Compose BOM のみでは View 用 Material3 テーマが解決できないため）。`Theme.Material3.*` に戻さないこと
- `core/pty-native` の `externalNativeBuild` と `ndkVersion` をコメントアウトしないこと（ビルドに必須）
- ターミナル描画は `Text` 行レンダリング（`TerminalView.kt`）。`Canvas#drawText` は初回 0 サイズでクラッシュするため使わないこと。不可視 IME フィールドは `clearAndSetSemantics`＋ミラーバッファ差分送信（P12、即時クリアは Gboard 再送と競合するため禁止）。キーボードタイプは `Password`＋`ImeAction.Go`（日本語レイアウトの全角変換を回避、ソフト Enter 送信用）

## 6. 変更履歴

- 2026-10-11 v1.3.0: 完全ターミナル化＋堅牢化。SSH セッション（sshj、password/鍵、host-key 信頼、known_hosts、全角正規化、実機 E2E 済み）・端末内検索（件数/前後/ハイライト/行コピー）・Links 一覧・split 2 ペイン・電池免除導線・SAF 実同期・Bridge UDS サーバ・他アプリ VIEW 受付・chroot 要root 明記。Round 3 監査修正（service 誤 stopSelf、writer 上限、shutdown 確実 reap、cancel 伝播、journal rotate、検索 debounce、tick 間引き、collector 漏れ、状態ローカライズ、診断 live 化、死資源削除）。211 unit tests PASS。TB710FU（Android 16）で E2E（SSH は loopback fixture、検索/分割/電池/タブ/SAF/Bridge/VIEW）。rootfs は `rootfs-13.7-r1` を継続使用。残: 実機 release smoke（USB 切断中のため再接続時に実施）、Mac↔device 同一 LAN 対向（別サブネットのため保留）、SOV40（Android 11）未接続
- 2026-10-10 v1.2.0: Debian 実働版。proot 同梱（5.1.107.96＋loader/loader32＋libs）・Debian 13.7-r1 署名配布（Ed25519＋RSA-2048 併用、API 30 対応）・純 JVM 導入パイプライン（resume DL＋TarExtractor＋atomic promote）・Linux セットアップ UI（doctor/preset 8 ボタン）・Node 24.21.0 プリセット（公式 tarball＋vendored xz＋LongLink 対応）・Claude 2.1.296/OpenCode 0.0.55 プリセット・localhost 200 到達・対話 zsh 入力修正（P12）。171 unit tests PASS。TB710FU（Android 16）で全 E2E、SOV40（Android 11）で Debian＋対話＋Node を検証。rootfs は `rootfs-13.7-r1` を継続使用
- 2026-10-10 v1.1.1: スキル適用版。`ui-ux-pro-max` v2.13.0 を `.opencode/skills/` に vendor し、48dp タッチターゲット・8dp 間隔・ブレークポイント別ガターを適用。`AppThemeTest` 追加。129 unit tests PASS。Android 16 実機で 48dp・縦横レトロを検証。SOV40（Android 11・スマホ縦画面）はロック中のため次回
- 2026-10-10 v1.1.0: 適応 UI 版。テーマ 4 種（system/light/dark/retro Win98 風＋ターミナル配色連動）、表示スケール（85〜130%）、縦横・解像度対応（landscape 常時 2 ペイン、terminal 高さは height class 連動、両 pane スクロール化）、自動最適化（`core/diagnostics/AutoTune`＋実機収集＋設定画面適用レポート、診断に network/空き容量行）。125 unit tests PASS（AutoTune/Contrast 含む）、日英 parity（full 130 / modern 37 / remote 37）。Android 16 実機で縦横・3 テーマ・スケール・auto-tune・日英を検証。SOV40（Android 11・スマホ縦画面）はロック中のため次回
- 2026-10-09 v1.0.1: 実機対応版。PTY 配線（`core/pty-runtime` + `core/terminal-session` 新設、`TerminalService` が host 所有、`TerminalView` で描画・入力・リサイズ）、Android shell 実機動作（Android 16 で検証）、診断の実測化（PTY/app-data exec/nested exec probe）、Debian/root の型付きゲート、`localeConfig`（日英 per-app）、rootfs Ed25519 署名鍵の発行・保管・公開鍵埋め込み・署名スクリプト。115 unit tests PASS、日英 parity（full 106 / modern 37 / remote 37）、16KB 再確認
- 2026-09-30 v1.0.0: 初回実装（P0–P3 + P4基盤）。101 unit tests PASS、assembleDebug 3 APK、16KB 23 .so PASS、日英 parity（full 94 / modern 37 / remote 37）、remote loader 分離 PASS。設計評価は `docs/DESIGN_EVALUATION.md`
