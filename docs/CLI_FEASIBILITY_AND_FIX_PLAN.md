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
- 対応: 公式 Node 24.21.0 LTS arm64 tarball をアプリ側で取得→
  ゲスト `~/.local/node` に展開（`.tar.xz` 対応のため純 Java xz を vendoring、
  `stripComponents=1` 対応）。apt 方式は dpkg の hardlink 問題（P10）で不採用。
  `~/.profile` に PATH 追記（冪等）。doctor は明示 PATH で検証。
  Claude は `npm i -g`＋`--version` まで（OAuth はユーザー操作のため対象外と明示）。
  OpenCode は公式 install script。localhost は Debian 内
  `python3 -m http.server`＋同 guest 内 `curl` で到達確認。

### P10. hardlink(2) が app-private で EPERM（実機で反証→設計に反映）
- `ln` がアプリ UID でも `Permission denied`（`touch`/`mkdir`/`rename`/`cp` は可、
  shell コンテキストの `/data/local/tmp` では touch すら不可）。
  `dpkg` の statusDB バックアップが hardlink 必須のため `apt-get install` は
  不可（`apt-get update` のリスト取得は可）。git の local clone 最適化も不可。
- 対応: インストーラ類は hardlink を使わない設計に限定（copy/rename のみ）。
  `TarExtractor` の hardlink は作成失敗時に skip（既存動作を維持）。
  apt install 系 preset は採用しない。

### P11. node-tar の LongLink 名解決バグ（単体テストで反証→修正済み）
- node 公式 tarball は 100 バイトに収まらないパスを GNU LongLink（`L`）で
  記録する。実装当初は strip 処理がヘッダ名（切詰め済み）に適用され、
  LongLink 解決が無視されていたため npm tree の ~90% が欠落
 （`graceful-fs` 不在で npm が起動不能に）。
- 対応: strip は解決済み名に適用。`NodeTarballReproTest`（最小ケース＋strip＋
  実 tarball 4000+ entries 検証）で回帰防止。

### P12. 対話入力が日本語 IME で全角化＋分割確定で重複（実機で反証→修正済み）
- `BasicTextField`（既定 Text タイプ）＋ Gboard 日本語フリック環境では、
  ASCII 確定が全角（U+FF48〜）に変換される（logcat で `U+ff48` を確認）。
  `KeyboardType.Ascii` 指定でも日本語レイアウトは無視するため、
  `Password` タイプ（半角英数固定・変換/サジェストなし）＋ `ImeAction.Go`
  （ソフト Enter を CR として送信、singleLine では DONE になるだけ）を採用。
- さらに `input text` 複数文字確定は Gboard が 1,2→1,2,3,4 のように
  先頭から再送する。実装当初の「毎回クリア＋末尾差分」はこれと競合して
  `echo hi`→`eechho  hi` の重複を生んだ（logcat 連番で確定）。
- 対応: バッファをミラー保持し最長共通接頭辞で差分のみ送信
  （削除は DEL/文字）。クリアは submit（Go/Enter）時とセッション切替時のみ。
  貼り付け複数文字が正確に 1 回届くことを TB710FU 実機で確認。
  全角保持自体は `TerminalEmulatorTest`（fullwidth round-trip）で保証。

### P5. proot の long option は `=` 結合必須（実機で反証→修正済み）
- 当初 `--rootfs <dir>`（空白区切り）で実装していたが、実バイナリ
  （proot 5.1.107.96）は `option '--rootfs' and its value must be
  separated by '='` で拒否。`--rootfs=<dir>` / `--bind=` / `--cwd=` に修正。
  実機エラー文が根拠。`ProotArgvTest` に回帰テスト追加。

### P6. `PROOT_TMP_DIR` 未設定では起動不可（実機で反証→修正済み）
- Termux 系 proot は prefix 内 TMPDIR を既定とするため、存在しないパスで
  `can't create temporary directory` となる。`filesDir/tmp` を作成し
  `PROOT_TMP_DIR` として付与（`ProotArgv.hostEnv`）。

### P7. 外部 loader がないと全 exec が ENOENT（実機＋ソースで特定→修正済み）
- Termux ビルドは `PROOT_UNBUNDLE_LOADER` 定義のため、exec ごとに外部
  `loader` バイナリを必要とする（`PROOT_LOADER` env または termux prefix
  の固定パス）。未同梱時は `get_loader_path()==NULL` → -ENOENT。
  ソース（`src/execve/enter.c`）と strings の両方で確認。
- 対応: `loader-arm64-v8a`＋`loader32-arm` を同梱し `PROOT_LOADER[_32]` を付与。
  静的リンクのため 16KB 問題なし。

### P8. Ed25519 は API 33+ のみ（公式リファレンスで確認→修正済み）
- Android の `Signature/KeyFactory` アルゴリズム表で Ed25519 は 33+。
  API 30 実機で manifest 検証が fail-closed となることを確認。
- 対応: RSA-2048（SHA256withRSA、全 API 可）の併用署名 `signatureRsa` を追加。
  ポリシー: Ed25519  capable → Ed25519 必須（RSA でマスク不可）、
  非 capable → RSA 必須。秘密鍵は common-rules に保管、公開鍵のみ埋め込み。
  `ManifestSignaturePolicyTest` で行列検証。

### P9. dpkg は非 root UID を拒否（実機で反証→修正済み）
- `apt-get install` が `dpkg: error: requested operation requires
  superuser privilege` で失敗。proot-distro 同様 `-0`（fake root）を付与。
  ホスト側の所有者は app UID のまま、Android サンドボックスも維持。

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
