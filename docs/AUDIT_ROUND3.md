# Round 3 精査: メモリ/クラッシュ/安定性/UI完全性（2026-10-11）

3 並行エージェントで監査 → 全件を原文で裏取り済み。幻覚なし。
凡例: S=SEVERE M=MAJOR m=minor。括弧内は修正バッチ。

## S級（必ず直す）

1. S1: service が初回 onCreate で即 stopSelf（空map replay）→ rotation の
   unbind で onDestroy → 全PTY 殺害。TerminalService.kt:83-91。
   修正: 非空を観測するまで stop しない (B1)
2. S2: writer Channel.UNLIMITED（paste 暴走＋stall で無限増殖）。
   TerminalSessionHost.kt:205。修正: 512/DROP_OLDEST＋drop カウンタ (B1)
3. S3: 検索が tick 毎に全 scrollback（最大10万行）を Main で再計算。
   MainActivity.kt:548-554。修正: 400ms debounce (B3)
4. S4: 描画 snapshot（300行×cell copy）が tick 毎に Main で再構成。
   TerminalView.kt:100-118。修正: VM で tick を sample(80ms) (B1)
5. S5: shutdown() が wait なしで close → zombie（nativeClose は erase のみ）。
   TerminalSessionHost.kt:166-180＋pty-native.cpp。修正: SIGTERM→2.5s待機→
   close の非同期 reaper＋isClosed＋host scope 保持/遅延 cancel (B1)

## M級（直す）

6. M1: VM の collector が bind 毎に4本漏れ（旧 host 拘束）。
   TerminalViewModel.kt:74-100。修正: Job 保持＋unbind/disconnect で cancel (B1)
7. M2: CancellationException 呑み（SshConnection auth/connect、
   SafSyncEngine importTree/resolveConflict）。修正: 先に rethrow (B1/B2)
8. M3: journal 無限 append＋1行破損で全滅。StorageMirrorManager.kt。
   修正: 500行 rotate＋行単位 try/catch＋useLines (B2)
9. M4: SSH ダイアログ破棄で keyBytes が残留。MainActivity。
   修正: dismiss/confirm で fill(0) (B3)
10. M5: spawn-while-stopping でゾンビ session 復活。SessionModel.start。
    修正: isClosed チェック＋spawn済み handle の即時 close (B1)
11. M6: host scope 永生（onDestroy で cancel なし）。修正: 保持＋5s遅延 cancel (B1)
12. M7: split_need_two 到達不能。MainActivity 244/494。修正: raw splitId＋件数条件 (B3)
13. M8: Links が全 session 横断。MainActivity 716-723。修正: sessionId スコープ (B3)
14. M9: 検索 300行 cap 以外は reveal 不能（無言）。修正: 上方一致の notice＋先頭へ (B3)
15. M10: エラー banner が sticky・dismiss 不可。修正: ✕＋成功時 clear (B3)
16. M11: BatterySection 未呼出（header のみ）。SettingsScreen 199/231。
    修正: header 下で呼ぶ (B3)
17. M12: 状態が生 enum（JAで英語）。MainActivity 361/369。修正: 4状態の資源化 (B3)
18. M13: StatusCards 固定文言＋processCount  stale。修正: live化＋Debian 実測＋改名 (B3)
19. M14: su 検出が Main（最大2s）。TerminalViewModel 72。修正: IO (B1)
20. M15: 鍵読込が Main。MainActivity 817。修正: IO (B3)
21. M16: Diagnostics が単発・SSH行なし・Node 常時 Unknown。修正: ping 再試行＋SSH行 (B3)
22. M17: readLoop が Default 占有。TerminalSessionHost 190。修正: IO で起動 (B1)

## m級（直す・安いもの）

- m1: FindBar ↑↓ contentDescription＋0/0 資源化＋copy 無効化
- m2: Close selected 無効化
- m3: SSH dialog: port 上限 inline・dialog 内 error・鍵クリア釦
- m4: Trust: Back→DENY＋N件中n表示
- m5: SSH 接続中プログレス（starting／承認待ち）
- m6: rememberSaveable（selectedTab/splitId/search/query、secrets 除外）
- m7: terminal_empty 資源＋使用
- m8: known_hosts 追記を synchronized
- m9: Bridge 接続毎スレッド（上限8）＋通知IDを method 単位に
- m10: inbox trySend 失敗の表面化＋上限剪定
- m11: tick を update{} 化
- m12: SshConnection.close で shell/streams 明示 close
- m13: conflict「他N件」＋LinuxSetup「truncated」注記
- m14: snapshotLines が emu.rows を使用
- m15: SAF に ensureActive＋cancel 釦（構造次第）
- m16: TFilterChip M3 側に 48dp
- m17: 死資源の削除（grep で未参照確認後に両 locale）
- m18: withPty 未使用の旨を文書化（将来用に保持）

## 見送り（理由つき）

- M3ダイアログの Retro 化、副ペイン ExtraKeys、onTrimMemory（trim 対象なし）、
  SSH keepalive（NAT 切断は正常終了扱い）、exit code 上書き合流、cursor 撕裂
  （1f の表示ずれのみ）、su… 以外の minor 観測系。

## 検証

- 208 tests 維持＋新規: journal rotate/破損行、connect cancel 伝播、writer drop。
- 実機: rotation で session 生存、SSH close で zombie なし（ps）、find＋大量出力
  で jank なし、trust Back で DENY、split 1件で hint。
