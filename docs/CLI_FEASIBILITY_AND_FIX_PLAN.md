# Claude Code / OpenCode / ローカルサーバー精査と修正計画（v1.2.0）

調査日: 2026-10-10 / 対象: `oriyu90/mterm` v1.1.1

## 1. 結論（先に）

現状では **Debian が存在しないため Claude Code / OpenCode / ローカルサーバーは一切動作しない**。
Android shell には node も python もない。以下4点の連鎖的問題があり、
すべて実機検証済みの根拠とともに修正する。

## 2. 問題点の詳細（実機・実物で確認済み）

### P1. proot バイナリが端末に存在しない（配布物欠落）
- `ProotBackend.spawn` は `filesDir/bin/proot` の存在を要求するが、APK にも
  端末にも存在しない。→ `SPAWN PROOT_MISSING` で即失敗（実機で確認済み）。
- 追加調査: Termux 公式 APT（`proot_5.1.107.96_aarch64.deb`）から抽出可能。
  依存は `libtalloc.so.2`＋`libandroid-shmem.so` のみで、同梱すれば
  Android 11/16 の両実機で `proot --version` が動作することを確認済み。
  ELF は 16KB align（0x4000）、interpreter は `/system/bin/linker64`。
- 対応: 3 ファイルを `distribution/proot/` に version-pin＋SHA 記録し、
  app-full の assets に同梱→初回起動時に `filesDir/bin/` へ配置＋chmod。
  起動時は `LD_LIBRARY_PATH=filesDir/bin` を付与する。

### P2. `ProotBackend` の argv は Termux 系 proot 用（検証済み・維持）
- 当初 `--rootfs/--bind/--cwd` を疑ったが、抽出した実バイナリの strings に
  `--rootfs/--bind/--cwd/--link2symlink` 等の long option を確認。
  Termux 系 proot では正しい形式のため **argv は変更しない**。
  真正性は rootfs 到着後の実機 Gate A で最終確認する。

### P3. Debian rootfs が存在しない（最大の欠落）
- `distribution/manifests/debian-trixie-arm64.json` は sha256/signature ともに
  `REPLACE_WITH_...` のプレースホルダ。DL・検証・展開の実装もない。
- 対応: Docker（arm64 ネイティブ）で `debootstrap trixie minbase`＋base
  パッケージを構築→strip→tar.gz→Ed25519 署名→GitHub Release 配布
  （`rootfs-13.7-r1` タグ）。純 JVM の tar.gz 展開器＋HttpURLConnection
  resume 付きダウンローダ＋staging/atomic 切替＋resolv.conf 初期化を実装。

### P4. Node / npm / CLI / localhost 検証が存在しない
- preset・doctor・E2E は計画書の記述のみでコードなし。
- 対応: Node 24 LTS 公式 arm64 tarball を preset として取得→Debian 内に配置。
  検証は `node/npm --version`、OpenCode（Go 製単体バイナリ）を
  `--version` で確認、Claude Code は `npm i -g`＋`--version` まで
  （OAuth はユーザー操作のため対象外と明示）。
  localhost は Debian 内 `python3 -m http.server`＋Android 側 `curl` で到達確認
  （同一 netns のため到達可能）。

## 3. 修正計画（順序）

1. `distribution/proot/` に 3 バイナリ＋VERSION/SHA を配置、app-full assets 同梱
   （`aaptOptions noCompress`）、`ProotInstaller`（配置＋chmod＋probe）を実装
2. `core/rootfs-manager` に `RootfsDownloader`（resume＋進捗Flow＋SHA/Ed25519 検証）、
   `TarGzExtractor`（純 JVM USTAR＋TarSafety＋symlink＋mode 復元）を実装＋unit test
3. rootfs Docker ビルド→署名→`rootfs-13.7-r1` Release 配布→manifest 本物化
   （`scripts/sign-rootfs.py` 使用、秘密鍵は common-rules から）
4. `ProotBackend` に `LD_LIBRARY_PATH` 付与、resolv.conf 初期化（INITIALIZING）
5. Linux セットアップ UI（DL 進捗・preset 導入・状態表示）＋日英文字列
6. 実機 E2E: Gate A（Debian zsh/apt/curl）→ node/npm → opencode --version →
   claude --version → localhost http.server＋curl 到達
7. 失敗時は staging 削除・既存 READY 非破壊（既存方針維持）
8. v1.2.0/10200 リリース＋サイト＋保守文書更新

## 4. スコープ外（明示）

- Claude Code の OAuth/API キー認証フロー（ユーザー操作が必要）
- apt full-upgrade の保証（ユーザー責任、snapshot 方針は計画書通り）
- modern/remote への rootfs 配線（Full のみ）
