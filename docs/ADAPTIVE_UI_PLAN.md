# Adaptive UI / Themes / Auto-tune — 実装計画 (v1.1.0)

根拠スキル: `hallmark-ui-design`（トークン起点・共有部品→画面固有の順、実画面検証＋自動ゲート）。
前提確認 (2026-10-10 時点):
- Material3 `window-size-class 1.3.1` は既存依存。新規 `material3-adaptive` は取得せず、既存 API＋`LocalConfiguration.orientation` で対応（オフライン build 安全）。
- `MainActivity` は `configChanges` で回転再生成なし＋service 所有 PTY のため、回転でセッションは維持される。不足はレイアウト分岐のみ。
- `theme` pref は存在するが `MainActivity` が無視している（常時 M3 default light）。ここを配線する。
- targetSdk 28 の Full 版で `isSystemInDarkTheme()` は利用可（Compose runtime 判定、API 依存なし）。

## 1. 画面回転・解像度対応

- 分岐: `sideBySide = (widthSizeClass == Expanded) || (orientation == LANDSCAPE)`。
  - 横画面の compact/medium 幅でも左右分割し、terminal の横幅を確保する。
- terminal 高さ: `heightSizeClass` 連動（Compact→240dp / Medium→320dp / Expanded→480dp）。固定 320dp を廃止し、タブレットの大画面と低丈 landscape の両立を図る。
- 両 pane を `verticalScroll` 化（低丈 landscape でのボタン欠け防止。hallmark: narrow/wide/low-height を実機確認）。
- PTY resize は既存 debounce 経路のまま（surface 追従）。

## 2. 表示スケール調整

- `displayScale` pref 追加（既定 1.0、選択肢 0.85 / 1.0 / 1.15 / 1.3）。
- `MTermRoot` で `CompositionLocalProvider(LocalDensity provides Density(density, fontScale * displayScale))`。
  - sp のみ拡大し dp レイアウトは不変（崩壊防止）。terminal 文字サイズは既存 slider と独立。
- 設定画面に % 表示の stepper。最小/最大で実画面確認する。

## 3. ダーク / ホワイトモード

- `theme` = system/light/dark を実際に配線。M3 baseline `lightColorScheme()` / `darkColorScheme()`（コントラスト実績あり）。
- terminal palette を theme 連動: dark=現行 TokyoNight 系 / light=紙白 bg＋墨 fg。
- 設定の 3 Switch（UX 不良）を FilterChip 4 択（System/Light/Dark/Retro）に置換。

## 4. レトロモード (Win98)

- `theme = "retro"` 追加。M3 スロットへの割付＋専用部品で UI 全体を Win98 化:
  - 配色: 画面 teal `#008080`、panel gray `#C0C0C0`、title navy `#000080`＋白太字、文字 black。
  - 部品: 角 0dp、`drawBehind` による bevel border（raised: 白上左/灰下右＋黒外周 / pressed: 反転）、`TButton` / `TTextButton` / `TCard` / `TTopBar` / `TFilterChip` を新設し全画面で使用。
  - terminal palette: black bg＋silver fg の DOS 窓。
  - コントラストは `Contrast` 純粋関数＋unit test で検証（本文 ≥4.5:1、境界 ≥3:1。disabled 灰のみ例外記録）。
- 通常時は M3 既定部品（見た目不変）。切替は `LocalRetro` CompositionLocal。

## 5. ネットワーク / ファイル自動最適化

- `core/diagnostics/AutoTune.kt`（純粋 JVM、unit test 可）:
  - `DeviceCaps`（transport/metered/validated/空き容量/RAM/SAF 許可数/pageSize/API）→ `decide()` → `TuneDecision(wifiOnlyDownload, autoMirrorSync, scrollbackLines, notes)`。
  - 規則: wifiOnly 常時 true（安全側）/ autoMirror は SAF 許可時のみ true / scrollback は低 RAM 時のみ縮小（拡大はしない）。
- アプリ層 `collectCaps()`（`ConnectivityManager`＋`File.usableSpace`＋`ActivityManager.MemoryInfo`、全 try/catch＋API guard）。
- 設定画面に「自動最適化」節: 実行ボタン＋適用結果レポート表示。診断画面に network/storage 行を追加。
- 将来の rootfs DL は `wifiOnlyDownload` を参照する（本版では pref のみ）。

## 6. 品質ゲート

- `StringsParityTest`（日英キー完全一致、新規キーも両言語追加）。
- 新規 unit tests: `AutoTuneTest`、`ContrastTest`。
- `./gradlew testDebugUnitTest`、`assembleDebug`（3 app）。
- 実機（TB710FU Android 16 / SOV40 Android 11）: 縦/横、最小/最大スケール、light/dark/retro、日英、auto-tune 実行。screencap＋uiautomator で確認。
- 対象は `app-full` のみ（modern/remote は実験・Play 殻のため対象外。共有 pref キーは core に置く）。

## 7. 検証記録（2026-10-10 / LENOVO TB710FU / v1.1.0 debug）

| 項目 | 結果 |
|---|---|
| 縦 portrait (tablet, expanded) | 左右 2 ペイン描画。セッション RUNNING・prompt 描画（dark 追従） |
| 横 landscape | 回転後も RUNNING 維持、2 ペイン継続、cols 拡大追従（height Compact→240dp） |
| system テーマ | 端末 dark 追従で M3 dark 適用（従来は常時 light の不具合を修正） |
| light テーマ | 白 UI＋紙白ターミナル＋墨カーソル。セッション動作 |
| retro テーマ | teal desktop＋navy タイトル＋gray raised ボタン/chip/panel＋黒 DOS 窓。`?`/`S` に content-desc。FATAL なし |
| 表示スケール 130% | 全体テキスト拡大、レイアウト崩壊なし |
| 自動最適化 | `Applied on …`＋`Network connection verified.`＋`No shared folder…`。Wi-Fi のみ ON 維持、mirror OFF（SAF なしのため正しい） |
| 日英 | per-app ja-JP で新文字列すべて日本語表示（レトロ/表示スケール/自動最適化節） |
| 診断追加行 | network/free-space 行を追加（本検証では目視省略、collector は auto-tune で実証済み） |
| 未実施 | SOV40 実機（パターンロック中）のスマホ縦画面、16KB 実機（両機 4KB のため CI のみ） |

## 8. ui-ux-pro-max スキル適用パス（2026-10-10 / v1.1.1）

スキル `ui-ux-pro-max` v2.13.0（MIT）を `.opencode/skills/ui-ux-pro-max/` に vendor し、search＋pro-rules チェックリストで UI を修正:

- 48dp タッチターゲット（pro-rules 高重要度×3）: retro の `TButton`/`TFilterChip`/`TSwitch`/`RetroSquareButton` を 40dp→48dp。M3 側は既定の minimum interactive size で適合。実機 uiautomator で 102px（48dp@340dpi）を確認
- 8dp 間隔（Touch Spacing）: extra-keys `LazyRow` を 4dp→8dp
- ブレークポイント別ガター（Adaptive gutters）: compact 16dp / expanded 24dp を pane 余白に適用
- 適用しなかった項目の記録: icon family 変更なし（Material Icons 一貫使用・絵文字なし）、 terminal 高さ値の変更なし（実機で問題なし）、`Canvas#drawText` 不使用方針を維持
- hallmark との調整: desktop 向け 40px 規定より mobile 向け 48dp を優先（Android アプリのため）

## 7. リリース

- 1.1.0 / versionCode 10100（3 app 同期）。README・`mterm.md`・紹介サイト・common-rules 文書は従来フローで更新。
