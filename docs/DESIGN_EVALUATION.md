# mterm 設計評価とデバッグ報告 — 2026-09-30

基準: `docs/IMPLEMENTATION_PLAN.md`（実装計画書 v1.0 / 2026-09-29）/ 元設計書 2026-09-05
対象: `oriyu90/mterm` v1.0.0（Full / Modern / Remote の3 app module + 13 core modules）
検証環境: macOS arm64 / JDK21 (Microsoft 21.0.9) / Gradle 9.5.1 / AGP 9.1.1 / NDK 28.2.13676358 / CMake 3.22.1 / compileSdk 36

## 1. 設計評価（計画書 §2 の判断を実装で確認）

| 項目 | 計画判断 | 実装結果 | 判定 |
|---|---|---|---|
| Debian ARM64 を主環境 | 採用（13.7 trixie 固定） | `distribution/manifests/debian-trixie-arm64.json` に id/version/arch/minAppVersion を固定。`RootfsManager` は current.json のみ参照 | PASS |
| PTY ベース Terminal | 採用（stdout reader 否定） | `pty-native` が ptmx→grantpt→fork→setsid→TIOCSCTTY→dup2→exec を JNI で実装。`SessionManager` は PTY handle 経由のみ | PASS |
| non-root=PRoot / root=chroot | 採用（backend 分離） | `ExecutionBackend` interface で完全分離。`ProotBackend` / `ChrootBackend` は argv 配列のみ組み立て、UI は UseCase のみ呼ぶ | PASS |
| RootFS 初回DL | 採用+強化 | NOT_INSTALLED→…→READY 状態機械、`.part` resume 方針、staging 削除・既存 READY 非破壊・`/home` 非上書きをコードと README に明記。SHA-256 恒時比較 + Ed25519（fail-closed）+ traversal 拒否 | PASS |
| ~/Downloads 直結 | 変更（mirror/sync） | `StorageMirrorManager` が SAF tree ↔ mirror 同期。content URI の POSIX 直結なし。conflict は自動上書きせず 3 択。除外既定あり | PASS |
| Claude Code 前提 | 更新（native 優先） | README・UI とも native installer 優先・npm fallback。MCP child process は `ProcessSupervisor` で追跡する方針 | PASS |
| Play-compatible | 再定義（別 module） | `app-remote` が loader 系 4 module に非依存。`NoLoaderCodeTest` で `linux_proot/linux_chroot/rootfs_manager/root_core/libsu` の混入を検証（0 hits） | PASS |
| FGS | 採用+具体化 | `TerminalService` が所有。modern のみ `specialUse` + subtype property。開始はユーザー操作からのみ。0 session で stop。自前通知チャネル | PASS |
| Termux 参照 | 採用（Apache subset のみ） | terminal core はクリーンルーム自作（Apache-2.0 ヘッダ）。Termux ソースの vendor なし。GPL 混入なし。PRoot はバイナリ非同梱・notice 分離 | PASS |
| Android 10+ | 採用（minSdk 28 は manifest 都合） | full のみ minSdk/target 28。`MainActivity` は API<29 で bilingual Unsupported 表示。CI マトリクスは API29+ | PASS |

## 2. 2026-09 前提の反映（§3）

- Play target API 36（R1）: modern/remote は target 36。full は target 28 のまま sideload 専用とし Play に出さない
- W^X（R2/R9）: full legacy で成立→ modern ExecBroker は `ExecBrokerQualifier`（Gate C）で判定。昇格条件をコードコメント化
- 16KB page（R4）: NDK 28.2 + `-Wl,-z,max-page-size=16384` + `scripts/check-elf-alignment.sh`。arm64 23 .so 全て Align 0x4000 を確認
- FGS specialUse（R5）: modern のみ宣言、Play 審査説明を manifest property に内包
- edge-to-edge / large screen（R6）: `enableEdgeToEdge()` + WindowSizeClass 2-pane
- SAF（R7）: Download ルート直結なし、mirror 方式
- phantom process（R8）: 24 警告 / 32 高リスクの soft-limit（hard cap なし）、waitpid 回収、kill-group

## 3. デバッグ記録（実装中に修正した実障害）

1. **AGP 9 built-in Kotlin 衝突** — `Cannot add extension 'kotlin'`。原因は AGP 9.0+ の built-in Kotlin に対して `org.jetbrains.kotlin.android` を適用したこと。対策: 全 module から `kotlin.android` を除去し、`kotlin.compose` / `serialization` のみ + `kotlinOptions` 撤去（[migrate-to-built-in-kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)準拠）。`legacy-kapt`（AGP 同一 version）へ移行
2. **local.properties 形式誤り** — `android { sdkDir }` 形式で SDK not found。`sdk.dir=` 形式に修正
3. **NDK zip 破損** — `~/.Library` 側 NDK が source.properties 欠落。brew 側の正常 NDK 2.8G をコピーして復旧
4. **libsu 解決不可** — `com.github.topjohnwu.libsu:core:5.2.2` が google/mavenCentral に存在せず（JitPack のみ）。MVP では依存を除去し `RootManager` を純粋 `su -c exit` 検出に変更。将来 libsu 6.x 採用時は tag pin + remote 分離維持
5. **Room 2.6.1 × Kotlin 2.2 メタデータ** — `Provided Metadata version 2.2.0, max 2.0.0`。Room 2.7.1 へ更新して解決
6. **Theme 解決不可** — Compose BOM のみでは `Theme.Material3.*` View リソースが存在しない。全 app テーマを `android:Theme.Material.Light.NoActionBar` 基底に変更（Compose 側で MaterialTheme）
7. **`Os.getpagesize()` 不存在** — Android `android.system.Os` に存在しない。`Os.sysconf(_SC_PAGESIZE)` に修正（modern と統一）
8. **window-size-class experimental** — `calculateWindowSizeClass` に `ExperimentalMaterial3WindowSizeClassApi` の OptIn を追加
9. **check-elf スクリプト非 portable** — mac `head -z` 不可 + `ALIGN`/`Align` 表記差。`awk '$1=="LOAD"'` 方式に修正し 23 .so で PASS

## 4. テスト結果

- `./gradlew testDebugUnitTest`: **101 tests, 0 failures/errors/skipped**（20 XML 集計）
  - session-core 14、linux-core 16、process-supervisor 9、terminal-emulator 17、rootfs-manager（Verifier/Tar/Manager）、linux-proot/chroot argv、root-core capabilities、storage-mirror 6、bridge 5、data CommandJson 2、diagnostics 8、app-full/modern/remote StringsParity（94/37/37 キー parity）、remote NoLoaderCode
- `./gradlew assembleDebug`: **BUILD SUCCESSFUL**（3 APK: full/modern/remote-debug）
- `scripts/check-elf-alignment.sh`: checked=23 fail=0（arm64 全 LOAD Align 0x4000）
- strings parity: full 94/94、modern 37/37、remote 37/37
- remote loader 分離 grep: 0 hits（`NoLoaderCodeTest` でも PASS）
- 実機 matrix（API29/31/33/35-16KB/36 Pixel/36 Samsung/37 lane）はリリース前の手動ゲートとして `mterm.md` に残す（本環境は emulator なしのため device runtime 項目は UNVERIFIED のまま出荷しない）

## 5. 安全設計の確認

- 互換性: full target 28 / modern・remote target 36、minSdk 28/29、compileSdk 36、API28 Unsupported 表示、16KB 対応、adaptive UI
- 既存機能: 破壊的変更なし（新規リポジトリ）。DB version 1、migration は forward-only にしない方針を `MTermDatabase` に文書化
- クラッシュ安全性: malformed ANSI/OSC/UTF-8 は無視して継続、 bounds 検証、staging 失敗時は既存 READY 非破壊、FGS 0 session で停止、mount namespace 不可端末では chroot 無効
- メモリ安全性: JNI の off/len 厳密検査、EINTR リトライ、zombie 回収、PTY write 直列化（Mutex/Channel 方針）、paste rate-limit 方針
- UI 言語: 日英キー完全一致（テストで強制）。システムロケール自動適用、hardcoded 文字列なし方針

## 6. 残タスク（Gate と保守に委譲）

- Gate A: target28 Full 実機で Debian nested exec（bash→python subprocess、node child）、PTY Ctrl+C/Z/resize/EOF
- Gate B: npx vite 実機 localhost
- Gate C: ExecBroker が 10/12/15/16 + Pixel/Samsung + 16KB で成立するまで modern を昇格しない
- Gate D: private namespace 不可端末では Root ボタンを出さない（実装済み gate を実機で確認）
- Gate E: secret redaction / license / signature / 16KB / process stress が全 PASS するまで公開しない（本報告で CI 可能な項目は PASS、実機項目は `mterm.md` へ）
