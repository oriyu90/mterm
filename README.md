# mterm — Android Linux Workstation Terminal

> Author: Yuki_Orita（折田悠希 / おりたゆうき）
> License: MIT — see [LICENSE](LICENSE)
> Repo: https://github.com/oriyu90/mterm
> Project page: https://studio-rizi.pages.dev/projects/mterm/

[日本語](#日本語) | [English](#english)

---

## 日本語

### 概要

**mterm** は、Android 上で Debian 13 (trixie / arm64) の userland を主環境として使うためのターミナル・ワークステーションです。Android の shell ではなく Debian を主環境とし、PTY ベースの Terminal Emulator → Session Manager → PRoot (non-root) / chroot (root) の二系統で Linux を実行します。

設計の詳細は [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md)（2026-09 実装計画書 v1.0、元設計書 2026-09-05 を具体化）に集約しています。本 README は実装の到達点と使い方の正本です。

### 3つのエディション（別 app module）

| Module | 役割 | SDK方針 | 配布 |
|---|---|---|---|
| `apps/app-full` | Full Sideload / MVP | minSdk 28 / targetSdk 28 / compileSdk 36 | GitHub Releases（sideload）。Android 10+ を正式QA対象。API28 起動時は未サポート表示 |
| `apps/app-modern` | Full Modern / Experimental | minSdk 29 / targetSdk 36 | ExecBroker（system linker 等）の成立性検証。Gate C 通過まで本命に昇格しない |
| `apps/app-remote` | Play-compatible / Later | minSdk 29 / targetSdk 36 | Debian ELF を外部取得しない。SSH / remote / Android shell 中心。Play 用コードを Full から物理分離 |

> 重要: Full 版を「少し調整すれば Play に出せる」という前提は捨てています。Full と Play 向けは共有 core を持つ別プロダクトとして設計し、Gradle flavor ではなく app module 自体を分けています（loader/download code の混入防止）。`app-remote` は `linux-proot` / `linux-chroot` / `rootfs-manager` / `root-core` に依存せず、`NoLoaderCodeTest` で物理分離を検証します。

### 到達点（MVP: P0〜P3 + P4基盤）

- 起動後数秒で zsh の対話セッションに入れる（rootfs ready 後）
- PTY ネイティブ（`/dev/ptmx` → fork → setsid → exec、process group 単位で SIGTERM→猶予→SIGKILL）
- Terminal core（ANSI/xterm サブセット、True Color、Unicode 幅・結合文字、scrollback 10k/最大100k、bracketed paste、alternate screen）
- signed rootfs installer（SHA-256 + Ed25519 + staging 展開 + atomic 切替、`/home` 非破壊、traversal/symlink escape 拒否）
- PRoot backend（argv 配列固定、shell 文字列連結なし、bridge/mirror bind）
- TerminalService + ProcessSupervisor（FGS はユーザー操作からのみ開始、phantom process 24 警告 / 32 高リスク、zombie 回収、session kill-group）
- SAF mirror/sync（content URI を POSIX path として扱わない。SAF tree ↔ app-private mirror 同期、conflict は自動上書きせず選択UI、node_modules/.git/build 除外）
- AndroidBridge（UDS 0600 + SO_PEERCRED、length-prefixed JSON、`open.url` / `open.path` / `clipboard.copy-paste` / `notification.show` / `app.info`、FileProvider 経由 open、background からの Activity 起動は notification に落とす）
- Node 24 LTS preset、Claude Code は native installer 優先・npm fallback（`claude --version` / `doctor` / MCP child process を E2E で検証）
- root chroot backend（libsu ではなく純粋 su 検出で MVP。private mount namespace が作れない端末では無効化し、global mount しない）
- Mac ライク UI（tabs / split 2-pane / extra keys 編集可能 / 選択コピー / True Color / large-screen adaptive、edge-to-edge）
- 日英完全対応（`values/strings.xml` と `values-ja/strings.xml` のキー完全一致を `StringsParityTest` で検証）
- 16KB page 対応（NDK 28.2、CMake 3.22、`-Wl,-z,max-page-size=16384`、`scripts/check-elf-alignment.sh` で検査）

### 使い方

1. Release から `mterm-full-*.apk` を sideload（提供元不明の許可が必要）
2. 初回起動で Debian 13.7 rootfs manifest を検証付きでダウンロード（`.part` resume、staging 展開）
3. New Session → Debian (PRoot) で zsh に入る
4. `git / ssh / python3 / node / npm / npx vite / tmux` を利用。localhost サーバーは Android ブラウザで開ける
5. Claude Code: `curl -fsSL https://claude.ai/install.sh | bash`（推奨）または `npm install -g @anthropic-ai/claude-code`（fallback）

詳細コマンドは `scripts/e2e-commands.sh` を参照。

### 安全な設計

- UI → su/mount/proot を直接呼ばない。`SessionManager` の UseCase のみ
- 書き込み可能 app data 上の ELF 実行は targetSdk 29+ で W^X 制約を受けるため、MVP は targetSdk 28 Full を先に成立させ、targetSdk 36 ExecBroker は別検証（Gate C）
- secret  handling: `~/.ssh` / credential / token は app-private。terminal 入出力・typed command を analytics/crash に送らない。clipboard history 非保存。backup export では秘密ファイルを default 除外
- 診断 export は `Redactor` で path/username/token を自動 redact
- ライセンス: terminal core は Apache-2.0 サブセットのみ vendor（GPLv3 app code を混ぜない）。PRoot GPL executable は source/notice を分離提供

### ビルド

```bash
# 前提: JDK17+, Android SDK (platforms android-36, build-tools 36, NDK 28.2.13676358, CMake 3.22.1)
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew :apps:app-full:assembleRelease \
  -Pandroid.injected.signing.store.file="$KEYSTORE_PATH" \
  -Pandroid.injected.signing.store.password="$STORE_PASSWORD"
bash scripts/check-elf-alignment.sh
```

署名鍵の一次保管は private リポジトリ `oriyu90/common-rules-document` の `keystores/mterm-upload-key.jks`（alias `upload`、パスワードは共通ルール文書参照）。本リポジトリに `.jks` を置かない。更新時はそこから鍵を持ってくること。

### テスト

- `./gradlew testDebugUnitTest`（ANSI/Unicode/PTY/Session/Rootfs tar/Bridge/SAF sync/strings parity/NoLoaderCode）
- 実機 matrix: API29 / 31 / 33 / 35(16KB) / 36 Pixel / 36 Samsung（必須 OEM gate）/ 37 lane
- `scripts/e2e-commands.sh`（git/ssh/python/node/npm/vite/gcc/tmux + fixture build）

### ドキュメント

- 設計・実装計画: [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md)
- 設計評価: [docs/DESIGN_EVALUATION.md](docs/DESIGN_EVALUATION.md)
- 保守メモ（次回更新点・鍵場所）: [mterm.md](mterm.md) — 公開サイトには載せない
- 紹介サイト（公開）: https://studio-rizi.pages.dev/projects/mterm/

### コミュニティ

- Discord（バグ報告・告知）: https://discord.gg/x7KXhNTD8M
- X: https://x.com/InovateofRIZI
- 開発者サイト: https://studio-rizi.pages.dev/

---

## English

### Overview

**mterm** is a terminal workstation that runs Debian 13 (trixie / arm64) userland as the primary environment on Android — not the Android shell. It uses a PTY-based Terminal Emulator → Session Manager → PRoot (non-root) / chroot (root) pipeline.

The full design is in [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) (Sep 2026 plan v1.0). This README is the normative implementation summary.

### Three editions (separate app modules)

| Module | Role | SDK | Distribution |
|---|---|---|---|
| `apps/app-full` | Full Sideload / MVP | minSdk 28 / targetSdk 28 / compileSdk 36 | GitHub Releases (sideload). QA on Android 10+. API28 shows Unsupported |
| `apps/app-modern` | Full Modern / Experimental | minSdk 29 / targetSdk 36 | ExecBroker qualification. Not promoted until Gate C passes |
| `apps/app-remote` | Play-compatible / Later | minSdk 29 / targetSdk 36 | No external Debian ELF. SSH/remote/Android-shell only, physically separated |

Full and Play editions are separate products sharing only core modules — never promoted by "small tweaks". `app-remote` has zero deps on loader cores, enforced by `NoLoaderCodeTest`.

### What works (MVP)

- zsh in seconds (once rootfs ready), PTY native with process-group cleanup
- Terminal core (ANSI/xterm subset, True Color, Unicode widths, 10k scrollback, bracketed paste, alt screen)
- Signed rootfs installer (SHA-256 + Ed25519, staged + atomic, never overwrites `/home`, traversal-safe)
- PRoot backend (fixed argv arrays, no shell concat), TerminalService FGS (user-gesture only), ProcessSupervisor (phantom warnings 24/32, zombie reaping)
- SAF mirror/sync (no direct content-URI POSIX mapping, conflict UI, excludes), AndroidBridge UDS (0600 + SO_PEERCRED, FileProvider open)
- Node 24 LTS, Claude Code native-installer-first, root chroot gated on private mount namespace, Mac-like adaptive UI, complete JA/EN parity, 16KB-page ready

### Usage

1. Sideload `mterm-full-*.apk` from Releases
2. First launch downloads verified Debian 13.7 rootfs (resumable, staged)
3. New Session → Debian (PRoot) for zsh
4. Use `git/ssh/python/node/npm/npx vite/tmux`; open localhost servers in Android browser
5. Claude Code via native installer (preferred) or npm fallback

### Safe design

- UI never calls su/mount/proot directly; argv arrays only; traversal-safe paths; no secrets in logs; no clipboard history; secrets excluded from backups by default; diagnostics redacted; Apache-only terminal vendor; PRoot GPL notices separated

### Build / Test

See Japanese section (same commands). Release keys live only in private `oriyu90/common-rules-document#keystores/mterm-upload-key.jks` (alias `upload`); never commit `.jks` here. On update, fetch the key from there.

### Docs / Community

- Plan: `docs/IMPLEMENTATION_PLAN.md`, Evaluation: `docs/DESIGN_EVALUATION.md`, Maintenance (not public): `mterm.md`
- Site: https://studio-rizi.pages.dev/projects/mterm/ · Discord: https://discord.gg/x7KXhNTD8M · X: https://x.com/InovateofRIZI
