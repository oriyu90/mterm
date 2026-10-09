# mterm 実機調査・問題分析・修正計画（v1.0.0 → v1.0.1）

- 調査日: 2026-10-09
- 対象: `oriyu90/mterm` v1.0.0（versionCode 10000 / 3 app modules + 13 cores）
- 実機（ADB）:
  - Android 16 / API 36 — LENOVO TB710FU（arm64-v8a, 4KB page, 2000x3200 / density 400）
  - Android 11 / API 30 — Sony SOV40（arm64-v8a, 4KB page, 参考回帰・今回はパターンロック中のため未実施）
- ビルド環境: macOS arm64 / JDK21 / Gradle 9.5.1 / AGP 9.1.1 / NDK 28.2.13676358 / CMake 3.22.1 / compileSdk 36
- 結果: **v1.0.1 として修正・実機検証済み**（§7 検証記録を参照）。115 unit tests PASS、日英 parity（full 106 / modern 37 / remote 37）

## 1. 実機で観測した挙動（事実）

`apps/app-full` の debug APK（`dev.studiorizi.mterm.full`）を Android 16 端末へインストールし、ADB で操作・ログ採取した。

| 操作 | 観測結果 |
|---|---|
| 起動 | `LaunchState: COLD / TotalTime 642ms`。クラッシュなし。adaptive UI（2-pane）描画。 |
| 「New Android shell」タップ | セッション chip「Android shell」が増えるが `State: -`、ターミナルは「Ready (device runtime required)」のまま。 |
| ログ | `AndroidRuntime` 例外なし。反面、PTY 起動・プロセス生成・描画更新のログも一切なし。 |
| 「New Debian (PRoot)」 | 同上（セッションが CREATED のまま）。 |
| 「New root chroot」 | 端末上で `rootChrootSupported != true` のため disabled。 |
| Diagnostics | `PTY=PASS`（`isAvailable()` のみ）/ `app-data exec・PRoot・Debian・nested exec・bridge` = 「この端末では未検証」。 |
| process count | 常に 0（`ProcessSupervisor` に何も登録されない）。 |

結論: **アプリは起動するが、ターミナルとしては何も動かない。** セッションは `CREATED` に留まり、`SessionManager.start()` が呼ばれる経路が存在しない。

## 2. 根本原因（コード根拠）

コアロジックは概ね実装・単体テスト済みだが、**アプリ層からの配線が丸ごと欠落**している。

1. **PTY spawn 未配線**
   - `core/pty-native` の C++（`pty-native.cpp`）は ptmx→grantpt→fork→setsid→TIOCSCTTY→dup2→exec まで完成。JNI も健全（bounds 検査・EINTR リトライ・zombie 回収）。
   - しかし全 backend の `spawn()` が `throw UnsupportedOperationException("PTY spawn requires device runtime; argv validated")`（`AndroidShellBackend` / `ProotBackend` / `ChrootBackend` / `SshBackend`）。
   - `PtyNative.nativeSpawnPty` を呼ぶコードがアプリ・コアのどこにも無い。
2. **Terminal Emulator 未使用**
   - `core/terminal-emulator`（727 行、17 tests）は完成しているが、`MainActivity` からは「placeholder」コメントのみで一切参照されない。描画サーフェスも入力処理も存在しない。
3. **SessionManager が二重管理**
   - `TerminalService.buildSessionManager()` と `TerminalViewModel.init` が**それぞれ別の `SessionManager`** を生成。`MainActivity` は どちらの `TerminalService` にも bind していない（import のみ）。
   - FGS も「New session」から start されない（`TerminalService.start()` 呼び出しが app-full に存在しない）。
4. **RootFS installer 未実装**
   - `RootfsManager` は `current.json` の読み取りのみ。ダウンロード・verify・extract を実行するコードと UI が無い。`distribution/manifests/debian-trixie-arm64.json` は `sha256/signature` が `REPLACE_WITH_...` のプレースホルダ。
   - proot バイナリも rootfs もリポジトリ・配布物に存在しない。
5. **AndroidBridge サーバ未実装**
   - `BridgeProtocol`（codec）と `tools/bridge-cli/*`（接続クライアント）はあるが、**UDS サーバ本体が存在しない**。`tools/bridge-cli/pbpaste.sh` などは接続先が無い。
6. **StorageScreen がデモ挙動**
   - `detectConflict(now, now, now-60000, "shared/demo.txt")` を毎回実行し、実同期を行わない（サンプル固定値）。SAF 選択後も mirror へ取り込まない。
7. **診断が実測でない**
   - `pty=PASS` は「ライブラリがロードできた」だけ。`app-data exec`/`nested exec`/`bridge` は `UNVERIFIED` 固定。
8. **ドキュメントと実装の乖離**
   - `docs/DESIGN_EVALUATION.md` は「PTY spawn を JNI で実装」= PASS としているが、JNI は呼ばれていない（"実装" と "動作" の混同）。README の到達点表現も実挙動より先行している。

## 3. 修正方針（安全設計の維持）

計画書 §19.2 の優先順（01: target28 Full PTY + `/system/bin/sh`、02: Emulator vendor + adapter）に従い、**Android 11/16 実機で本当に動くターミナル**を成立させる。Debian/root は同一 PTY 配線に載せ、配布物（proot/rootfs）が無い端末では**未導入を正確にゲート表示**する。既存のモジュール境界・公開 API・単体テストは最大限維持する。

### 3.1 追加モジュール
- `core/pty-runtime`: `PtyNative` を包む `PtyProcess`（`PtyHandle` + `ProcessHandle` 両実装）と `PtyRuntime.spawn()`。JNI へ `nativePid(handle)` を追加。
- `core/terminal-session`: `TerminalSessionHost`（セッション生成・PTY 読み取りループ→`TerminalEmulator` 投入・write/resize・終了ポリシー）と、共有 `AndroidShellBackend`。

### 3.2 既存インターフェース拡張（後方互換重視）
- `PtyHandle` に `read()/write()` を**デフォルト実装付き**で追加（既存実装は無改修でコンパイル可）。
- `ExecutionBackend.spawn(prepared, rows, cols): SpawnedProcess` へ変更（全実装を更新）。
- `SpawnException(SpawnFailure)` を導入し、未導入理由を UI で日英表示。

### 3.3 アプリ層
- `TerminalService` が `TerminalSessionHost` を所有。Activity は bind して host を取得（回転・再生成で PTY を保持）。「New session」= ユーザー操作から `startForegroundService`。
- `TerminalViewModel` は service の host をミラー。`TerminalScreen` に Canvas 描画の `TerminalView`（True Color パレット・カーソル・スクロールバック）+ 透明入力フィールド（IME/物理キー）+ extra keys（ESC/TAB/CTRL/ALT/矢印, DataStore 編集対応）。
- resize はレイアウトから rows/cols を算出し debounce して `TIOCSWINSZ` + `SIGWINCH`。
- 終了は SIGTERM→猶予→SIGKILL（process group）、reader ループで `waitpid` 回収。

### 3.4 Debian / root の正確なゲート
- `ProotBackend.spawn`: `proot` 実行ファイルと rootfs の存在を確認し、無ければ `SpawnException(PROOT_MISSING/ROOTFS_MISSING)`。rootfs 導入済みなら同一 PTY で起動。
- `ChrootBackend`: private mount namespace 非対応端末では UI から無効（既存 gate 維持）。spawn は明示的に `ROOT_UNSUPPORTED`。
- rootfs 署名用 Ed25519 鍵ペアを発行（秘密鍵は common-rules-document に保管、公開鍵のみアプリ埋め込み）。署名スクリプトを追加。

### 3.5 診断の実測化
- PTY: `nativeSpawnPty(["/system/bin/sh","-c","echo ok"])` の実 probe。
- app-data exec: app data に実行ビット付きスクリプトを書き `sh script` で実行する実 probe（target 28 は W^X 対象外）。
- process count / bridge は実状態を反映。

## 4. スコープ外（今回は配布基盤が外部のため保留）
- Debian rootfs / proot バイナリの実配布（ホスティング + 署名）。コードパスと検証ロジック・鍵は用意するが、未導入端末ではゲート表示。
- AndroidBridge UDS サーバ本体と CLI 接続、SAF↔mirror の実 I/O 同期（v1.0.2 以降の候補として `mterm.md` に記録）。

## 5. 検証計画
1. `./gradlew testDebugUnitTest`（既存 101 + 追加テスト）
2. `./gradlew :apps:app-full:assembleDebug` と Release（release key で署名）
3. Android 16 / 11 実機: Android shell で `echo/printf/色/ls/Ctrl+C/resize/回転/scrollback`、複数セッション、FGS 通知、Stop all、異常系（proot 未導入時の明示エラー）、クラッシュなし・メモリ。
4. `scripts/check-elf-alignment.sh`（16KB）
5. 日英 parity テスト

## 6. 受け入れ条件（Gate A 前半）
- Android shell が実機で**実際に対話動作**する（入力→echo、Ctrl+C で割り込み、resize 反映、EOF 終了）。
- セッションは回転・Activity 再生成を跨いで維持（service 所有）。
- Debian/root は「未導入」または「非対応」を正確に表示し、握りつぶさない（`CREATED` のまま放置しない）。
- クラッシュ・ANR・FD/プロセスリークなし。日英 UI 完全一致。

## 7. 検証記録（2026-10-09 / Android 16 LENOVO TB710FU / v1.0.1 debug ビルド）

| 項目 | 結果 |
|---|---|
| 起動 | COLD 525ms、クラッシュなし |
| New Android shell | `RUNNING`、child process 登録（supervisor count 1） |
| プロンプト描画 | `:/ $` を `Text` 行レンダリングで表示（True Color パレット・ブロックカーソル） |
| 入力→実行→出力 | `gcd` → `not found` + 終了コード 127 を次プロンプトに表示、折り返しあり |
| 診断実測 | PTY PASS / app-data exec PASS / nested exec PASS（いずれも実 probe） |
| Debian ボタン | `SpawnException(PROOT_MISSING)` → 日英ゲート文を表示 |
| root chroot ボタン | su なし端末で disabled（`su=false mntns=false`） |
| Stop all | セッション除去、`Session exited (code 137)` 表示、FGS 通知消去・サービス停止 |
| 回転 | セッション維持、cols 追従（landscape で幅拡大） |
| 日英 UI | per-app `ja-JP` で全画面日本語表示（キー欠落なし）。`localeConfig` 追加 |
| FGS | セッション実行中に通知、0 件で停止 |
| 描画クラッシュ修正 | `Canvas#drawText` の初回 0 サイズ例外を `Text` 行描画へ変更。不可視 IME フィールドは `clearAndSetSemantics` + 即時リセット |
| 未実施 | Android 11 実機（SOV40 パターンロック中）、Ctrl+C 割り込みの実機確認、16KB 実機（両機とも 4KB page のため CI スクリプトのみ） |
