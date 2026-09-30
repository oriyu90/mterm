Android Linux Workstation Terminal
実装計画書 — 2026年9月版
添付設計書を実装レベルへ具体化し、Android 16 / Google Play要件 / W^X / 16 KB page / FGS / SAF / PRoot / Claude Code の現状を反映
結論: まず sideload 前提の「本当に使える Full Linux build」を完成させる。Play Store対応は同一バイナリの延長ではなく、実行コード取得を行わない別エディションとして扱う。
基準日: 2026-09-29  /  元設計書基準日: 2026-09-05
対象: Android 10+（MVPの実機QA対象） / arm64-v8a優先
 
目次
1. エグゼクティブサマリー
2. 元設計書の評価と修正点
3. 2026年9月時点のAndroid前提
4. 確定アーキテクチャ
5. ビルド・配布戦略
6. 推奨技術スタックとバージョン
7. リポジトリ / モジュール構成
8. Terminal Emulator / PTY / Session実装
9. Debian / PRoot / RootFS実装
10. Lifecycle・Foreground Service・プロセス管理
11. Androidストレージ統合とBridge
12. Node / Claude Code / AI CLI対応
13. Root chroot backend
14. セキュリティ・更新・バックアップ
15. 実装フェーズと完了条件
16. テスト計画と互換性マトリクス
17. リスクと技術判断ゲート
18. 主要API・設定例
19. 参考資料
1. エグゼクティブサマリー
元設計書の中心思想は妥当である。Android shellではなく Debian ARM64 userland を主環境とし、Terminal Emulator → PTY → Session Manager → PRoot（non-root）/ chroot（root）の二系統を持つ構成は、そのまま採用できる。
ただし、実装開始前に3点を設計上の「固定条件」に変更する必要がある。
1. Full Linux版はまず sideload 配布を前提にする。任意の Debian ELF を apt 等で取得して実行する機能は、Google Play の現在の実行コード取得ポリシーと衝突するためである。[R1][R3]
2. 書き込み可能な app data 上の ELF 実行は targetSdk 29+ ではW^X制約を受ける。MVPは実績のある legacy-target Full build を先に成立させ、targetSdk 36対応の ExecBroker は別の技術検証として進める。[R2][R9]
3. Android共有フォルダは content URI をそのまま POSIX path として扱わない。SAF tree ↔ app-private mirror の同期方式を基本とし、Linux側には通常のディレクトリとして見せる。[R7]
最重要: 「Play対応を後で少し調整すればFull版をそのまま出せる」という前提は捨てる。Full版とPlay向け版は、共有coreを持つ別プロダクト/別app moduleとして設計する。
1.1 実装後の到達点
• 起動後数秒で zsh の対話セッションに入れる。
• git / ssh / python / Node.js / npm / npx / compiler / tmux が Debian 13 上で動作する。
• Vite等の localhost 開発サーバーを起動し、Androidブラウザで開ける。
• Claude CodeをLinux ARM64環境として導入・起動し、プロジェクト編集・git操作・MCP child processを検証できる。
• root端末では、明示的なユーザー操作により独立mount namespace内でchroot backendを利用できる。
• タブ・split・extra keys・選択/コピー・True Color・Unicodeを持つMacライクなUIを提供する。
2. 元設計書の評価と修正点
項目	判断	実装上の扱い
Debian ARM64を主環境	採用	2026-09時点のstableはDebian 13.7 (trixie)。MVP固定ディストリビューションにする。[R11]
PTYベースTerminal	採用	対話shellに必須。Runtime.exec()のstdout reader方式は採用しない。
non-root=PRoot / root=chroot	採用	責務分離は正しい。backend interfaceで完全分離する。
RootFSを初回DL	採用+強化	signed manifest + SHA-256 + Ed25519 + resumable download + staging展開 + atomic切替にする。
~/DownloadsをAndroid Downloadsへ直結	変更	SAFではDownloadルートをtree選択できない。mirror/sync方式にする。[R7]
Claude Code = Node.js 18+前提	更新	2026年はnative installerが実用的。npmはfallback。NodeはWeb開発用途として別途24 LTSを推奨。[R12][R13]
Play-compatibleを将来検討	再定義	Full版のPlay化ではなく、実行コードを外部取得しないremote/SSH中心版として別app module化する。[R1][R3]
Foreground Service	採用+具体化	target 34+ではservice type必須。terminal維持はspecialUse候補。ユーザー操作からのみ開始。[R5]
Termux terminal modules参照	採用	terminal-view / terminal-emulatorはApache-2.0例外。対象ソースをpinしてvendorし、ライセンスヘッダを保持。[R10]
Android 10+	採用	実機QAの最低ライン。legacy Fullのmanifest minSdkは28になるが、製品サポートはAPI29+に限定する。

3. 2026年9月時点のAndroid前提
3.1 Target API / Google Play
2026年8月31日以降、Google Playへ提出する新規アプリと更新は Android 16（API 36）以上をtargetにする必要がある。[R1] したがって Full版を target 28 のまま Play に出す構成は成立しない。
3.2 W^X / app data executable
Android 10をtargetするアプリでは、書き込み可能なアプリホームディレクトリに置いたファイルを直接 execve() することが制限される。Debian rootfsを /data/user/0/<pkg>/files/linux/... に展開し、その /usr/bin/* を普通にexecする設計は targetSdk 29+ で詰まる。[R2]
Termux系では古いtarget SDKを維持する方法に加え、system linker経由の実行回避策が知られているが、後者は本プロジェクトでAndroid 10〜16・主要OEM・16KB端末まで実機検証してから採用する。[R9]
3.3 16 KB memory page
Android 15以降は16 KB page size端末が存在し、NDKを使うアプリは対応が必要である。Google Playでは target 35+ の64bitアプリに16 KB対応が要求され、2027-02-01以降は非対応更新が公開できない。[R4] Full sideload版でも将来互換性のため、PTY/JNI/terminal native部品を含め最初から16 KB対応を必須にする。
3.4 Foreground Service
target 34+ ではForeground Service typeの宣言が必要で、既存typeに当てはまらない正当な用途には specialUse がある。Play提出時には用途説明も審査対象になる。[R5] Android 12+ではバックグラウンドからのFGS開始も原則制限されるため、TerminalServiceはユーザーがセッションを開始した操作から起動する。
3.5 Android 16 UI / large screen
target 36ではedge-to-edgeのopt-outがなくなり、600dp以上の大画面ではorientation/resizability/aspect-ratio制限が原則無視される。UIは最初からWindowSizeClassベースのadaptive layoutとし、スマホ1pane・タブレット2paneを同一Compose treeで扱う。[R6]
3.6 SAF
Android 11+では ACTION_OPEN_DOCUMENT_TREE で内部ストレージrootやDownloadディレクトリroot等を選択できない。またcontent URIはPOSIX pathではない。[R7] したがって「Linuxの~/DownloadsをAndroid Downloadsへ直接symlink」というUXは、そのままでは実装しない。
3.7 子プロセス / phantom process
TermuxはAndroid 12+で多数のphantom child processがOSにkillされ得ることを継続して警告している。[R8] Node/npm/Claude/MCPはプロセス数を増やすため、SessionManagerとは別にProcessSupervisorを持ち、子プロセス数のsoft-limit警告、waitpid回収、session kill-groupを実装する。
4. 確定アーキテクチャ
Compose UI / Navigation
        │
        ▼
TerminalSurface (Termux emulator core + View adapter)
        │ bytes / key events
        ▼
SessionManager ───────────── SessionRepository(Room)
        │
        ├── AndroidShellBackend ── PTY ── /system/bin/sh
        ├── ProotBackend ───────── PTY ── PRoot ── Debian 13
        ├── ChrootBackend ──────── PTY ── su/unshare/chroot ── Debian 13
        └── SshBackend (later) ─── PTY/client
        │
        ▼
TerminalService + ProcessSupervisor
        │
        ├── RootfsManager / UpdateManager
        ├── StorageMirrorManager (SAF ↔ local mirror)
        └── AndroidBridgeServer (UDS + SO_PEERCRED)

UI → su/mount/proot を直接呼ばない。UIはSessionManagerのUseCaseのみを呼び、backend・rootfs・bridgeを差し替え可能にする。
4.1 中核インターフェース
interface ExecutionBackend {
    val mode: SessionMode
    suspend fun prepare(spec: SessionSpec): PreparedSession
    suspend fun spawn(prepared: PreparedSession, pty: PtyHandle): ProcessHandle
    suspend fun stop(handle: ProcessHandle, signal: UnixSignal = SIGTERM)
}

interface RootfsManager {
    suspend fun ensureInstalled(channel: RootfsChannel): RootfsInstall
    suspend fun verify(install: RootfsInstall): VerificationResult
    suspend fun migrate(from: RootfsVersion, to: RootfsVersion)
}

interface AndroidBridge {
    suspend fun open(request: OpenRequest): BridgeResult
    suspend fun copy(data: ByteArray, sensitive: Boolean): BridgeResult
    suspend fun paste(): ByteArray
    suspend fun notify(request: NotifyRequest): BridgeResult
}

5. ビルド・配布戦略
5.1 3つのapp targetを分ける
Module	役割	SDK方針	備考
app-full	Full Sideload / MVP	minSdk 28 / targetSdk 28 / compileSdk 36	Android 10+を正式QA対象。app-data ELF直接実行を成立させる。GitHub/F-Droid系配布。
app-modern	Full Modern / Experimental	minSdk 29 / targetSdk 36 / compileSdk 36	ExecBroker（system linker等）の成立性を検証。成功後にFullの本命へ昇格。
app-remote	Play-compatible / Later	minSdk 29 / targetSdk 36 / compileSdk 36	Debian ELFを外部DLしない。SSH/remote workspace/Android shell中心。Play用コードをFullから物理分離。

app-full の minSdk 28 は targetSdk 28 以下にするためのmanifest上の都合であり、製品サポートとCIはAndroid 10（API29）以上に限定する。API28起動時は「未サポート」と表示して終了してよい。
MVPを app-modern の成功に依存させない。W^X突破の検証に失敗しても、Full Sideloadの開発を止めないことが重要。
5.2 Play版を別moduleにする理由
• PlayはGoogle Play外から dex/JAR/.so等の実行コードを取得するアプリを原則禁止している。[R3]
• aptでDebianのnative executableを自由に追加するFull版の価値と、Play policyは構造的に衝突する。
• Gradle flavorだけで隠すと誤ってPlay bundleにloader/download codeが混入するリスクがあるため、app module自体を分ける。
• 共通化するのは terminal-core / ui / session model / SSH 等に限定する。
6. 推奨技術スタックとバージョン
層	採用	方針
Android Gradle Plugin	9.3.3系	AGP 9.3はAPI 37まで対応。Gradle 9.5.0 / JDK17を基準。[R14]
compile / target	compileSdk 36 / modern target 36	Playの2026要件に一致。Full legacyのみtarget 28。
Kotlin	2.2.10 baseline	AGPの文書化されたbaselineをまず採用。Kotlin 2.4.20は別branchで互換性CI通過後に更新。[R15]
Jetpack Compose	BOM 2026.09.00	stable BOMで揃える。[R16]
NDK	28.2.13676358	AGP 9.3既定。全.soを16KB alignmentで検査。
JDK	17	AGP 9.3の標準。
DB	Room	Session metadata / rootfs install / mount mapping / sync journal。
Preferences	DataStore	UI設定・terminal設定・feature flags。
Native	C++17 or C++20 + CMake	PTY, signal, waitpid, process supervisor補助のみ。
Root	libsu 6.x	root shell / optional root service。pinしたtagを使用。[R17]
Linux	Debian 13.7 trixie arm64	2026-09-12時点stable。[R11]
Node	24 LTS推奨	Node 26はCurrent、24はLTS。[R12]
Claude Code	native installer優先	npmはfallback。Linux arm64 compatibilityをE2Eで検証。[R13]

7. リポジトリ / モジュール構成
android-linux-workstation/
├─ apps/
│  ├─ app-full/                 # sideload MVP target28
│  ├─ app-modern/               # target36 experimental
│  └─ app-remote/               # future Play build
├─ feature/
│  ├─ terminal-ui/
│  ├─ sessions-ui/
│  ├─ settings-ui/
│  ├─ storage-ui/
│  └─ diagnostics-ui/
├─ core/
│  ├─ terminal-emulator/        # vendored Apache-2.0 Termux subset
│  ├─ terminal-surface/         # AndroidView/Compose adapter
│  ├─ pty-native/               # NDK/JNI
│  ├─ session-core/
│  ├─ process-supervisor/
│  ├─ linux-core/
│  ├─ linux-proot/
│  ├─ linux-chroot/
│  ├─ rootfs-manager/
│  ├─ root-core/
│  ├─ storage-mirror/
│  ├─ android-bridge/
│  ├─ data/
│  └─ diagnostics/
├─ tools/
│  ├─ bridge-cli/               # open / pbcopy / pbpaste / notify
│  └─ terminal-doctor/
├─ distribution/
│  ├─ rootfs/
│  ├─ manifests/
│  ├─ licenses/
│  └─ sbom/
├─ build-logic/
├─ scripts/
└─ docs/

7.1 依存ルール
• feature/UI → use case / interface のみ。nativeやsuを直接参照しない。
• session-core は ExecutionBackend interface を知るが、PRoot/chrootの具体実装を知らない。
• root-core は app-full/app-modern からのみ参照。app-remoteには依存を入れない。
• android-bridgeはAndroid APIを持つため、Linux側bridge-cliとprotocol schemaを別moduleにする。
• TermuxからvendorするのはApache-2.0対象のterminal-emulator/terminal-view相当だけとし、GPLv3 app codeを混ぜない。[R10]
8. Terminal Emulator / PTY / Session実装
8.1 Terminal coreは再実装しない
最初からANSI/xterm parser・screen buffer・wide/combining characterを自作すると、MVPのリスクが大きすぎる。TermuxのApache-2.0例外対象terminal codeをpinしたcommitからvendorし、package rename・16KB native alignment・Compose adapterのみ加える。
UI chrome（tabs / split / toolbar / extra keys / settings）はComposeで作り、terminal surfaceだけはPhase 1で AndroidView wrapper を使う。性能・IME・選択操作を安定させた後、必要ならCanvas/Compose描画へ移行する。
8.2 PTY native API
external fun nativeSpawnPty(
    argv: Array<String>,
    env: Array<String>,
    cwd: String?,
    rows: Int, columns: Int
): Long // native handle

external fun nativeRead(handle: Long, buffer: ByteArray): Int
external fun nativeWrite(handle: Long, data: ByteArray, off: Int, len: Int): Int
external fun nativeResize(handle: Long, rows: Int, columns: Int): Int
external fun nativeSignal(handle: Long, signal: Int): Int
external fun nativeWait(handle: Long): ExitStatus
external fun nativeClose(handle: Long)

C++側は /dev/ptmx → grantpt/unlockpt → fork → setsid → TIOCSCTTY → dup2(0/1/2) → chdir → exec の順で起動する。sessionごとにprocess groupを作り、終了時はSIGTERM→猶予→SIGKILLの順でgroup cleanupする。
8.3 I/O設計
• PTY read: dedicated Dispatchers.IO coroutine。4–16 KiB chunkでterminal emulatorへ投入。
• PTY write: Channel<ByteArray>を1本に直列化し、IME/physical keyboard/pasteが競合しないようにする。
• resize: Compose layoutからcolumns/rows算出後、100ms debounceしてTIOCSWINSZ + SIGWINCH。
• paste: bracketed paste modeを尊重。巨大pasteは64KiB単位でrate limit。
• scrollback: terminal core側を行数上限（default 10k、最大100k）で管理。永続保存はdefault off。
8.4 Keyboard / IME
KeyEventはphysical keyboardとIME text inputを分離する。Ctrl/Alt/Meta/Esc/Tab/Home/End/PageUp/PageDownはKeyEvent経由、通常文字はInputConnection経由。extra keysはESC/CTRL/ALT/TAB/↑↓←→を最小構成とし、ユーザー編集可能な行定義をDataStoreに保存する。
9. Debian / PRoot / RootFS実装
9.1 Debian baseline
2026-09時点のstableは Debian 13.7 “trixie” で、arm64が公式対応されている。[R11] RootFS manifestは “debian-trixie-arm64” を固定IDとし、point release番号はmetadataとして保持する。
9.2 RootFS build pipeline
1. CIのARM64 Linux runnerで mmdebstrap/debootstrap を使い trixie minbase を生成する。
2. base packageだけを追加し、developer presetは初回起動後のapt taskに分離する。
3. /etc/resolv.conf, locale, default user, sudoers, zshrcテンプレートをrootfs image側で準備する。
4. 不要なmachine-id, host key, apt cache, logsを削除し、端末ごとに初回生成するものを空にする。
5. rootfs.tar.zst を生成し、SHA-256・size・package inventory・SBOMをmanifestへ記録する。
6. manifest本体をEd25519で署名する。アプリには公開鍵だけを埋め込む。
7. GitHub Releases/静的CDNへarchiveとmanifestを公開する。
{
  "schema": 1,
  "id": "debian-trixie-arm64",
  "version": "13.7-r1",
  "arch": "arm64",
  "sha256": "...",
  "size": 123456789,
  "minAppVersion": 10000,
  "createdAt": "2026-09-29T00:00:00Z",
  "signature": "ed25519:..."
}

9.3 Installer state machine
NOT_INSTALLED
  → DOWNLOADING (.part + resume)
  → VERIFYING
  → EXTRACTING (staging/)
  → INITIALIZING
  → READY

失敗時: stagingを削除し、既存READY環境は破壊しない。
展開先は app-private files/linux/distributions/debian/<version>/rootfs。current symlink相当の切替はAndroid/Java側metadataで行い、ファイルシステムsymlink依存を減らす。アップデートで既存 /home を上書きしない。
9.4 PRoot backend
PRootはptraceでsyscall/pathを仲介するため、native chrootより遅く、filesystem-heavy workloadで性能低下がある。[R18] それでもnon-rootでDebian userlandを成立させる現実的な選択肢なのでMVP採用する。
proot 
  --rootfs <rootfs> 
  --bind <bridge-dir>:/run/android-bridge 
  --bind <workspace-mirror>:/mnt/shared 
  --cwd /home/user 
  /usr/bin/env -i HOME=/home/user USER=user TERM=xterm-256color \n  PATH=/usr/local/bin:/usr/bin:/bin SHELL=/bin/zsh /bin/zsh -l
実際のCLI引数はtermux/proot forkの仕様に合わせて固定し、shell string連結は避けてargv配列で渡す。
9.5 Package preset
Preset	内容	実装
base	zsh, ca-certificates, curl, wget, git, openssh-client, procps, less, sudo, locales	rootfs imageに含める
web	node 24 LTS / npm / corepack / build-essential / pkg-config	初回preset task
python	python3 / pip / venv / build-essential	初回preset task
native	clang/gcc / cmake / ninja / make	初回preset task
tools	ripgrep / fd-find / jq / neovim / tmux	初回preset task
ai	Claude Code / Gemini CLI / Codex CLI等	アプリ内にバージョンを固定しすぎず、ユーザー起動installerを管理

10. Lifecycle・Foreground Service・プロセス管理
10.1 TerminalService
SessionManagerはActivity/ViewModel内に置かず、application process内のTerminalServiceに所有させる。ActivityはbindしてFlowを購読する。回転・split-screen・Activity再生成でPTYを落とさない。
MainActivity / Compose
       │ bind
       ▼
TerminalService (foreground while sessions active)
       │
       ├─ SessionManager
       ├─ ProcessSupervisor
       └─ BridgeServer

modern targetでは foregroundServiceType="specialUse" を宣言し、PROPERTY_SPECIAL_USE_FGS_SUBTYPEに「user-visible interactive terminal sessions and their child processes」の趣旨を明記する。[R5] サービス開始はユーザーの「New Session」操作からのみ行う。
10.2 Notification
• Channel: “Active terminal sessions”
• 表示: “2 terminal sessions running” + 最後にactiveだったsession title
• Action: “Open”, “Stop all”
• セッションが0になれば stopForeground + stopSelf。
• POST_NOTIFICATIONS拒否時でもFGS自体は動く場合があるが、UI内で状態を明確にする。[R19-notification]
10.3 ProcessSupervisor
• 各sessionにroot PID / process group / backend / start timeを登録。
• JNI waitpid loopでzombieを確実に回収。
• Linux shellから派生したdescendant数を可能な範囲で計測し、24でwarning、32付近でhigh-risk表示。数値はOS/OEMで変動し得るためhard capにはしない。
• アプリ終了時は「sessionを残す/終了」を明示。OS killからの完全復元は保証しない。
• tmuxはActivity切替やshell再接続には有効だが、AndroidがUID配下のprocessをkillした場合の保証には使わない。
11. Androidストレージ統合とBridge
11.1 SAFはmirror mountとして扱う
Linux CLIはrename/atomic write/symlink/file lock等のPOSIX semanticsを期待するため、DocumentFileを直接filesystemとして見せるFUSE風実装はMVPで行わない。選択したSAF treeを app-private mirrorへ同期し、Debianにはmirrorをbindする。
Android SAF tree (content://...)
       ⇅ sync engine
files/shared/<mountId>/mirror/
       ⇅ PRoot bind / chroot bind
/home/user/Shared/<name>

Sync policy
• Import: SAF → mirror。初回は全走査。
• Export: mirror → SAF。terminal session終了時/手動Sync/アプリforeground時にdebounce。
• Journal: relativePath, size, mtime, optional sha256, direction, lastSyncedVersion。
• Conflict: 両側変更を検出したら自動上書きせず「Keep Android / Keep Linux / Duplicate」を表示。
• 大規模node_modules/.git/buildはデフォルトで同期対象から除外できる。
11.2 AndroidBridge protocol
BridgeServerはapp-private Unix Domain Socketを作成し、Linux側の小さなbridge-cliが接続する。同一UIDのsocket権限0600に加え、SO_PEERCREDでpeer UIDを検証する。
Request (length-prefixed JSON)
{ "v":1, "id":"...", "method":"clipboard.copy", "params":{...} }

Methods
- open.url
- open.path
- clipboard.copy
- clipboard.paste
- notification.show
- app.info

pbpasteはAndroid 10+のclipboard privacyにより、アプリがforegroundでないと取得できないケースを正常系として扱う。bridgeはpermission denied相当のエラーを返し、CLI側は理由を表示する。[R20]
11.3 open path
1. guest pathをRootfsPathMapperでhost app-private pathへ正規化する。`..` escapeを拒否。
2. FileProviderで一時content URIを生成する。
3. MIMEを判定してACTION_VIEW。URLの場合はACTION_VIEWへ直接渡す。
4. backgroundからActivity起動が必要なケースでは直接開かずnotification actionに落とす。
12. Node / Claude Code / AI CLI対応
12.1 Node.js
2026-09時点でNode 24 “Krypton” がLTS、Node 26はCurrentである。[R12] デフォルトdeveloper presetはNode 24 LTSとし、ユーザーがnvm/fnm等で追加バージョンを管理できる設計にする。
Debian stableのnodejs packageに完全依存せず、version manager用のディレクトリは /home/user/.local/share/node 等、通常ユーザーが書ける範囲へ置く。
12.2 Claude Code
原案の「npm install -g」固定は更新する。AnthropicはLinuxでnative installerも提供しており、npm方式も残っている。[R13] 本アプリではnative installerを優先し、npmをfallbackとする。
# 推奨（ユーザー操作で実行）
curl -fsSL https://claude.ai/install.sh | bash
claude --version
claude doctor

# fallback
npm install -g @anthropic-ai/claude-code

Android自体はClaude Codeの公式サポートOSではない。Linux ARM64 / Debian userlandとしての互換性を「機能保証」ではなく、バージョンごとのcompatibility testとして維持する。
Claude E2E acceptance
• install script / npm fallbackのどちらかが成功。
• claude --version / claude doctorが成功。
• OAuth/API key認証フローがAndroidブラウザとの往復で完了。
• 10k files程度のrepoでscan・edit・git diffが動く。
• MCP stdio serverを1つ起動し、子processの終了・再起動が追跡できる。
• Ctrl+C, terminal resize, alternate screen, bracketed pasteが壊れない。
13. Root chroot backend
root機能はMVP後半まで入れない。non-root基盤が安定してから同じTerminal/Session APIの別backendとして追加する。libsuはroot shell/root serviceの管理に使い、UIから直接suを呼ばない。[R17]
13.1 起動フロー
1. RootManagerがlibsuでgrant状態を確認。自動promptしない。
2. root session作成時のみsuを要求。
3. 可能なら `unshare -m` 相当でprivate mount namespaceを作る。
4. namespace内で / をrprivateにしてmount propagationをhost側へ漏らさない。
5. /dev をbind、/procをmount、/sysは必要時のみread-only bind。
6. workspace/bridge用ディレクトリだけbind。
7. chroot(rootfs)後、HOME/USER/PATH/TERMを設定してzshをPTYへexec。
8. 終了時はnamespace内mountをcleanup。namespaceを作れない端末ではroot chrootを「unsupported」と判定し、global mountを安易に行わない。
Root modeの失敗をnon-rootモードへ波及させない。root capabilityは detectSu / privateMountNamespace / bindMount / chroot の4項目に分けて診断する。
14. セキュリティ・更新・バックアップ
14.1 Secret handling
• ~/.ssh, Git credential, Claude/API credentialsはapp-private領域。
• terminal stdout/stderr/typed commandをanalytics/crash reportへ送らない。
• デバッグログはpid, exitCode, backend, version等のmetadataのみ。
• clipboard historyは保存しない。copy時にsensitive flagを指定できるbridge optionを用意。
• backup exportでは ~/.ssh, credential files, token filesをdefault除外し、明示選択時だけ含める。
14.2 RootFS supply chain
• HTTPSだけに依存せず、manifest signatureをverify。
• archive SHA-256をverify。
• 展開時にabsolute path / .. traversal / symlink escapeを拒否。
• rootfs releaseごとにSBOMとpackage inventoryを保存。
• PRoot・bridge-cli等のnative executableはapp releaseと一緒に署名・version固定し、rootfs側から勝手に置換しない。
14.3 Updateの分離
更新面	仕組み	ロールバック
Android app	APK/AAB release	前version APKへ戻せる。DB migrationはforward-onlyにしない。
RootFS base	signed manifest + staged install	current version metadataを戻す。/homeは共有。
Debian packages	apt update/upgrade	ユーザー責任。rootfs snapshot/backupを用意。
AI CLI	各CLIのinstaller/update	tool managerはversion表示とhealth checkのみ。

15. 実装フェーズと完了条件
AIを併用した1人開発でも、低レベルAndroid互換性と実機検証は自動生成だけでは詰め切れない。以下は「コード量」ではなく、リスクを先に潰す順序である。工数は開発者日ベースの目安で、端末検証の待ち時間は含めない。
Phase	目的	目安	Exit criteria
P0	実行方式スパイク	5–8日	target28 Fullで /system/bin/sh→PTY、PRoot→Debian true/bash、apt、nested execを通す。同時にtarget36 ExecBroker prototypeを作る。
P1	Terminal core	8–12日	Termux core vendor、TerminalView adapter、IME/physical key、resize、selection、copy/paste、scrollback。
P2	Debian Full	10–15日	signed rootfs installer、PRoot backend、zsh/apt/git/python/node、npx viteまで。
P3	Lifecycle/Workspace	8–12日	FGS/service、ProcessSupervisor、Room restore metadata、SAF mirror/sync、open/pbcopy/pbpaste。
P4	Developer/AI	6–10日	SSH/tmux/toolchains、Node24 preset、Claude Code E2E、MCP child process。
P5	Root backend	5–8日	libsu、private mount namespace、chroot、cleanup、diagnostics。
P6	Mac-like UX	8–12日	tabs/split/search/theme/font/URL detection/large-screen adaptive。
P7	Hardening	8–12日	16KB、OEM test、fuzz/unit/E2E、backup/update、license/SBOM、release pipeline。

最初の公開可能MVPはP0〜P3。AI Coding Terminalとしての完成判定はP4通過。RootはP5で後付けする。
15.1 P0で絶対に確認すること
• Android 10 / 12 / 14 / 16 でtarget28 Fullのapp-private executableが起動できる。
• PRoot配下でbash → python → subprocess、node → npm → child processのnested execが動く。
• PTYでCtrl+C / Ctrl+Z / resize / EOFが正しく伝播する。
• target36 ExecBrokerでapp-private glibc ELFを起動できるかを判定。成功してもnested exec・shebang・dynamic linkerまで通ること。
• Samsung Android 16実機でPRoot performance regressionを計測。rootfs展開/apt/npm buildのベンチを記録する。[R18-issue]
16. テスト計画と互換性マトリクス
16.1 必須端末 / OS matrix
OS	環境	主な検証
API29 / Android10	arm64実機 or emulator	Full legacy baseline、storage、clipboard、PTY
API31 / Android12	arm64実機	phantom process、FGS、node/npm並列
API33 / Android13	実機	notification permission、clipboard UX
API35 / Android15	16KB emulator + 実機	16KB native、minimum target behavior
API36 / Android16	Pixel系実機	edge-to-edge、large screen、FGS、PRoot
API36 / Android16	Samsung実機	PRoot性能/OEM差。必須OEM gate。
API37 lane	emulator（利用可能時）	next API regression。release blockingではない。

16.2 Unit / native tests
• ANSI/OSC/SGR parser regression suite（vendor upstream test + 自前ケース）。
• Unicode: CJK, emoji, combining, wide char, Nerd Font glyph width。
• PTY native: spawn/read/write/resize/signal/wait/invalid fd。
• Session state machine: CREATED→STARTING→RUNNING→EXITED/FAILED。
• Rootfs tar extractor: traversal, symlink escape, corrupt zstd, bad signature。
• SAF sync: add/edit/delete/conflict/large file/interrupted copy。
• Bridge protocol: malformed length, oversized payload, wrong peer UID, path traversal。
16.3 E2E command suite
set -e
git --version
ssh -V
python3 --version
python3 -c 'import subprocess; subprocess.check_call(["/bin/echo","ok"])'
node --version
npm --version
npx --yes vite --version
gcc --version || clang --version
cmake --version
tmux -V

# fixture project
git clone <fixture> ~/Projects/fixture
cd ~/Projects/fixture
npm ci
npm run build
python3 -m venv .venv

16.4 Performance budgets
指標	目標	Fail時
Cold app launch	< 1.5s（terminal無し）	Compose/DB初期化をlazy化
New Android shell	< 500ms	PTY/JNI tracing
New Debian shell	< 1.5s（rootfs ready）	PRoot args / zsh init削減
Terminal input echo	p95 < 50ms	render batch / I/O channel調整
Scroll 10k lines	60fps目標 / jank最小	terminal renderer profiling
RootFS install	端末依存、進捗が停止しない	streaming extract / zstd tuning
Idle service	CPU≈0%、wake lock無し	poll loop/flowを修正

17. リスクと技術判断ゲート
ID	内容	重大度	対策
RISK-01	target36で任意ELF実行不可	Critical	P0でExecBrokerを試す。失敗ならFullはtarget28を継続し、modernをexperimentalのまま分離。
RISK-02	Play policyとapt/CLIの衝突	Critical	FullをPlayへ出さない。app-remoteを別moduleにする。
RISK-03	PRoot性能 / Android16 OEM差	High	Samsung/Pixel実測。重いbuildはroot backend/remote SSHを代替として案内。
RISK-04	phantom process kill	High	ProcessSupervisor、warning、session数制御、tmux推奨。ただしOS killを完全回避しない。
RISK-05	SAFとPOSIX semanticsの不一致	High	mirror/sync。direct content URI mountをMVPから外す。
RISK-06	Claude Code更新で互換性崩壊	Medium	nightly compatibility testではなく、release時のmanual/E2E matrix。アプリ本体と疎結合。
RISK-07	16KB native不整合	High	CIでELF alignmentチェック。vendor native libsを再ビルド。
RISK-08	root mount leak	Critical	private mount namespaceが作れない端末ではchroot backendを無効化。
RISK-09	ライセンス混入	High	Termux vendor範囲をApache subsetに限定。PRoot GPL executableはsource/noticeを分離して提供。
RISK-10	RootFS supply chain	High	signed manifest + SHA256 + staged extraction + SBOM。

17.1 Go / No-Goゲート
1. Gate A — P0: target28 FullでDebian nested execが通らなければ、PRoot/packagingを先に修正しUI開発を拡大しない。
2. Gate B — P2: npx viteが実機で動くまでClaude Code対応へ進まない。
3. Gate C — Modern: target36 ExecBrokerがAndroid 10/12/15/16 + Pixel/Samsung + 16KBで通るまでFull本命へ昇格しない。
4. Gate D — Root: private mount namespaceが確認できない端末にはRoot Debianボタンを出さない。
5. Gate E — Release: secret redaction / license inventory / rootfs signature / 16KB / process stressが全てpassするまで公開しない。
18. 主要API・設定例
18.1 Session model
data class SessionSpec(
    val id: String,
    val mode: SessionMode,
    val title: String,
    val cwd: String?,
    val env: Map<String, String>,
    val command: List<String>
)

data class SessionRuntime(
    val spec: SessionSpec,
    val state: StateFlow<SessionState>,
    val process: ProcessHandle?,
    val pty: PtyHandle?,
    val createdAt: Instant
)

18.2 TerminalService manifest（modern app概念例）
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".service.TerminalService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="User-visible interactive terminal sessions and child processes" />
</service>

18.3 Gradle baseline
// modern app
android {
    namespace = "..."
    compileSdk = 36
    defaultConfig {
        minSdk = 29
        targetSdk = 36
    }
    ndkVersion = "28.2.13676358"
}

// legacy Full app
// minSdk = 28, targetSdk = 28, compileSdk = 36

AGP 9.3.x / Gradle 9.5.0 / JDK17をpinし、version catalogとGradle wrapperをrepositoryへcommitする。[R14]
18.4 Diagnostics schema
CompatibilityReport
- androidApi / manufacturer / model / abi / pageSize
- appTargetSdk / buildVariant
- pty: PASS|FAIL
- appDataExec: PASS|FAIL
- proot: PASS|FAIL + version
- debian: PASS|FAIL + version
- nestedExec: PASS|FAIL
- node/npm: versions
- processCount: observed
- root: su / mountNamespace / bind / chroot
- storage: SAF grants / mirror health
- bridge: socket / clipboard availability

診断レポートのexportではpath・username・command history・tokenを自動redactする。
19. 参考資料（2026-09調査）
[R1] Google Play target API level requirement — 2026-08-31から新規/更新はAndroid 16 (API 36)+ — https://developer.android.com/google/play/requirements/target-sdk
[R2] Android 10 behavior changes — writable app homeからのexecve制約 — https://developer.android.com/about/versions/10/behavior-changes-10
[R3] Google Play Device and Network Abuse — Google Play外からのexecutable code取得制限 — https://support.google.com/googleplay/android-developer/answer/16559646
[R4] Android 16 KB page size support — https://developer.android.com/guide/practices/page-sizes
[R5] Foreground service types — specialUse — https://developer.android.com/develop/background-work/services/fgs/service-types
[R6] Android 16 target behavior changes — https://developer.android.com/about/versions/16/behavior-changes-16
[R7] Storage Access Framework / document tree restrictions — https://developer.android.com/training/data-storage/shared/documents-files
[R8] Termux app README — Android 12+ phantom process warning — https://github.com/termux/termux-app
[R9] Termux execution environment — app data file execute restrictions / system_linker_exec — https://github.com/termux/termux-packages/wiki/Termux-execution-environment
[R10] Termux LICENSE — terminal-view / terminal-emulator Apache-2.0 exception — https://github.com/termux/termux-app/blob/master/LICENSE.md
[R11] Debian Releases — Debian 13.7 trixie stable — https://www.debian.org/releases/
[R12] Node.js releases — v24 LTS / v26 Current — https://nodejs.org/en/about/previous-releases
[R13] Claude Code setup — https://docs.anthropic.com/en/docs/claude-code/getting-started
[R14] Android Gradle Plugin 9.3 release notes — https://developer.android.com/build/releases/agp-9-3-0-release-notes
[R15] Kotlin releases — 2.4.20 latest as of 2026-09 — https://kotlinlang.org/docs/releases.html
[R16] Jetpack Compose BOM — https://developer.android.com/develop/ui/compose/bom
[R17] libsu — https://github.com/topjohnwu/libsu
[R18] termux/proot-distro — limitations / Android-specific operational issues — https://github.com/termux/proot-distro
[R19] Android 15 all-app changes — minimum installable target API 24 — https://developer.android.com/about/versions/15/behavior-changes-all
[R20] Secure clipboard handling — https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling
19.1 元設計書から引き継ぐもの
元設計書の「Android shellを主環境にしない」「PTYベース」「root/non-rootを別backend」「UIとprocess lifetimeを分離」「PlayをMVP前提にしない」という5判断は、本計画でも維持する。変更したのは、それらを2026年9月の制約に合わせてビルド分離・実行方式ゲート・SAF mirror方式・Claude native installer等まで具体化した点である。
19.2 最終実装優先順位
01  target28 Full: PTY + /system/bin/sh
02  Terminal emulator vendor + Compose adapter
03  RootFS installer + Debian 13.7
04  PRoot nested exec + apt
05  git / python / Node24 / npm / npx vite
06  TerminalService + ProcessSupervisor
07  Storage mirror + Android bridge
08  SSH / tmux / compiler toolchain
09  Claude Code + MCP E2E
10  tabs / split / search / themes
11  root chroot backend
12  target36 ExecBroker qualification
13  backup / update / security / licenses / release hardening
14  optional app-remote Play edition

この順序なら「見た目は完成したがLinux実行基盤が成立しない」という最悪の失敗を避けられる。まず実行・PTY・PRootを実機で通し、その上にUXを積む。
Document status: Implementation plan v1.0 / Research cutoff: 2026-09-29. External policies and third-party CLI behavior can change; release前にR1–R20を再確認する。
