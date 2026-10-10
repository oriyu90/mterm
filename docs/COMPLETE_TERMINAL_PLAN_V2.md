# 完全ターミナル化計画 v2.0（Full target28 本命）

策定: 2026-10-10 / 前提: v1.2.0 リリース済み（Debian E2E 通過、171 tests）
根拠: 実機検証で発覚した未実装の棚卸し＋コード全量調査（2 並行エージェント）

## 1. 現状の真実（検証・コード両建て）

| # | 領域 | 状態 | 根拠 |
|---|---|---|---|
| G1 | SAF 実 I/O | 未実装（UI のみ） | `DocumentFile` 参照ゼロ、`StorageScreen` の Sync now は固定サンプル、`treeUri` は remember 止まり |
| G2 | Bridge UDS サーバ | 未実装（guest CLI のみ） | `BridgeServer*` ファイル不在、`bridge-cli.sh` は exit 69 固定、`DiagnosticsScreen` は `bridge=unsupported` 直書き |
| G3 | 他アプリ API | なし | exported は MainActivity のみ、Service/Provider は `exported=false` |
| G4 | 電池最適化導線 | なし | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 参照ゼロ、FGS のみ |
| G5 | タブ close/統一 | 部分的 | chip 選択はあるが ✕ なし、選択 state が 2 箇所に重複 |
| G6 | split/search/選択 | なし | TerminalView は単面、`find` API なし、長押し選択なし |
| G7 | SSH 実装 | スタブ | `SshBackend` は throw、`newSsh` は文字列表示のみ |
| G8 | chroot 本番化 | スタブ | `prepare` 成功→`spawn` 必ず失敗の紛らわしい UX |
| G9 | modern/remote 実行 | 未配線 | SessionManager のみ、start なし |
| G10 | 下位互換の汚れ | 要修正 | `RootfsPathMapper` の旧 prefix、modern の直書き path、filepaths 重複 |

## 2. v2.0 スコープ（今回やる）

1. **SAF 実同期エンジン**（G1）: SAF tree → `shared/<mountId>/mirror/` の再帰コピー（DocumentFile、journal＋excludes＋conflict KEEP/DUPLICATE 実コピー、`treeUri` 永続化）
2. **Bridge UDS サーバ**（G2）: `TerminalService` 所有の LocalServerSocket（0600、`bridge.sock`）、`notification.show/clipboard.copy-paste/open.url-path/app.info` 実装＋`bridge-cli` 一式をゲスト `/usr/local/bin` へ配置
3. **他アプリ受付の最小安全形**（G3）: `VIEW`（content URI → `shared/inbox` 取込＋Debian セッションを開く）のみ。`RUN_COMMAND` 系は RCE のため採用しない
4. **電池最適化導線**（G4）: 設定＋診断に状態行と要求フロー（拒否時も動作継続）
5. **タブ close＋選択統一**（G5）
6. **乖離修正**（G10）: mapper prefix、modern path、filepaths

## 3. v2.1 以降（今回やらない・理由つき）

- SSH 実装: sshj 依存＋鍵管理の大工事。`SshBackend` stub 維持
- split/選択・URL タップ: UI 大工事。検索 core API は先行実装済み（下記 Round 2）
- tmux プリセット: apt不可（hardlink P10）のため静的ビルド調達が前提。見送り
- chroot: root 取得デバイスなしでは検証不能。ボタン文言を「要 root」に明確化 ✅（Round 2 で対応）
- modern/remote 実行配線・Gate C: 実験版のまま。Full に集中
- BackupAgent・コンパイラ同梱: 別計画

## 3b. Round 2 作業順（2026-10-11 開始）

1. ✅ R2-A: chroot ボタン文言「要 root」明確化（EN/JA）＋ E4 はコード完成扱い
   （設定ボタン＋診断行＋フォールバック実装済み。Lenovo TB710FU に
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 解決 Activity が存在せず、
   タップ E2E は当該デバイスでは検証不能のため）
2. ✅ R2-B1: 端末検索 core API（`TerminalSearch`: scrollback＋画面の統一
   index 検索、大文字小文字無視切替、URL 抽出＋末尾句読点 trim）＋ 8 tests
3. R2-B2: 検索バー UI（次/前、ハイライト）＋長押し選択コピー＋URL タップ open
4. R2-C: split 表示（2 ペイン）
5. R2-D: SSH（sshj 依存の是非・鍵管理設計から。要相談）
6. R2-E: modern/remote 配線評価、BackupAgent（別途）

## 4. 依存関係図（今回分）

```
[SAF tree (OS)] --DocumentFile--> [SafSyncEngine] --File--> shared/<mountId>/mirror/
                                                                    |
[guest /mnt/shared] <--proot --bind-- [ProotArgv/Backend]            | (同一実体)
                                                                    v
[DiagnosticsScreen] <--journal-- [StorageMirrorManager] (既存流用)
[Settings/AutoTune] <--safGrants (既存流用)

[guest bridge-cli] --UDS /run/android-bridge/bridge.sock--> [BridgeServer (Service所有)]
        |-> BridgeCodec (既存) -> BridgePathPolicy (既存) -> {NotificationManager,
             ClipboardManager, FileProvider+VIEW, app.info}

[他アプリ VIEW] --> [InboxActivity or receiver] --> shared/inbox/ + open Debian
[Settings/Diagnostics] --> [BatteryExemption (ACTION_REQUEST...)]
```

## 5. 受け入れ条件（E2E）

- E1: SAF フォルダ選択→Sync now→ホスト mirror に実ファイル＋journal 記録→ゲスト `/mnt/shared` から可視
  → ✅ TB710FU 実機 PASS（Download/mterm-share 選択→`Copied 1`→ゲスト `cat` で内容一致）
- E2: ゲスト `bridge-cli notify.sh`→Android 通知表示、`pbpaste.sh`→クリップボード取得
  → ✅ 実機 PASS（`ok:true posted`、pbcopy/pbpaste 往復一致。実装中に二重ブレース JSON バグ・pbcopy 実体重複・LocalSocket タイムアウト UOE を潰した）
- E3: 他アプリからファイル共有→`shared/inbox` に保存＋Debian セッション起動
  → ✅ 実機 PASS（MediaStore URI＋grant で VIEW→inbox 保存＋Debian RUNNING）
- E4: 電池設定で「制限なし」要求フロー＋診断に状態表示
  → ✅ 診断行は実機表示確認（`Battery: optimized...`）。設定ボタンはコード＋フィルタ存在確認、タップ E2E は次回
- E5: タブ ✕ でセッション終了、選択統一（回帰なし）
  → ✅ 実機 PASS（2 セッション→Close selected→1 残、詳細追従）
- E6: 既存 171 tests＋新規 tests 全 PASS、debug 実機で E1–E5 確認後に commit
  → ✅ 184 tests PASS（debug 実機 E1–E3/E5 確認済み、E4 は部分的）

## 6. リスクと対策

- R1: SAF 大量ファイルで ANR → チャンク＋進捗 Flow、1000 ファイル上限・残りは次回
- R2: UDS のなりすまし → 0600＋SO_PEERCRED（将来）、当面は同一 UID 前提＋path policy 維持
- R3: VIEW 受付の悪意ファイル → inbox 隔離＋実行権限を付けない＋サイズ上限 32MB
- R4: R8 による LocalSocket/DocumentFile 削除 → 実機 smoke（SOV40 手順どおり）
