# 凯美瑞 CarPlay 连接界面接入说明

把 `redesign/diplay-redesign.html` 的 Apple / CarPlay 风格设计落地到连接等待界面。只替换
`CarPlayHostActivity` 的等待层，不改主界面 `DiPlayActivity`、不改动设置页、不动连接状态机。

## 改动文件

| 文件 | 作用 |
|---|---|
| `common/src/main/assets/carplay-connection.html` | 新增。原型改造为可嵌入页面：`?embedded=1` 铺满屏幕、去掉浏览器预览外框、隐藏预览控件、状态栏显示真实时间、默认关闭动效；所有按钮改为发 `diplay://action/<name>`。 |
| `common/src/main/java/com/shilapi/xcertplay/connection/CamryConnectionScreen.kt` | 新增。WebView 容器，唯一持有 `ConnectionProgress` 模型；把状态投影成 JSON 推给页面；同时把所有调用转发给原 `ConnectionProgressPanel` 作为降级。 |
| `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` | 修改。等待层加一层全屏 `connectionScreen`；12 处面板调用改走统一门面；新增动作桥接与按钮显隐同步。 |

## 结构

```
connectionPanel (viewport)
├── preparationScroll   GONE（正常） / VISIBLE（降级）
│   └── ConnectionProgressPanel + 原生重试 / 恢复 / 反馈 / 返回按钮
└── CamryConnectionScreen   全屏 WebView（正常）
```

`connectionPanel` 的显隐逻辑（首帧前显示、有视频流后隐藏）和窗口 inset 处理保持原样。

## 数据流

- **状态**：`ConnectionProgress` → `CamryConnectionScreen.render()` →
  `window.applyConnectionState(json)`。JSON 字段：`night`（`light`/`night`）、`motion`、
  `outcome`（`CONNECTING`/`WAITING`/`RETRYING`/`COMPLETE`/`FAILED`/`CANCELLED`）、
  `channel`（`pioneer`/`wireless`/`wired`）、`attempt`、`percent`、`currentTitle`、
  `sub`、`hint`、`estimate`、`summary`、`steps[]`（`title`/`state`/`time`）。
  所有文案来自现有 `R.string`，包括 PIONEER_WIRELESS 专属步骤名与车机蓝牙 / Wi-Fi 提示。
- **主题**：`setNight()` → `applyPalette({accent})`，accent 取自 `DiPlayPalette`。
- **按钮显隐**：`syncConnectionScreenActions()` → `window.applyActions(json)`，
  由原生 `startupRetryButton` / `wifiRecoveryButton` / `feedbackButton` 的可见性决定。
- **失败引导**：`showConnectionHelp()` → `setGuidance()`，使用
  `ConnectionFailureGuidance.message(...)` 的原文案。
- **状态行**：`setConnectionStage()` → `setStage()`，去掉换行后显示在副标题。
- **网页动作**：`diplay://action/back|nav-home|nav-settings|nav-connect|retry|reset|feedback`
  → `shouldOverrideUrlLoading` 拦截 → `handleConnectionScreenAction()`。
  其中 `retry` / `reset` / `feedback` 直接调用原生按钮的 `performClick()`，
  完整复用原有守卫（后台会话 owner、关闭中、菜单打开、握手重置）与业务逻辑。
  `nav-home` → `showDiPlayHome()`，`nav-settings` → `openSettingsMenu()`，
  `back` → `showDiPlayHome()`。

## 降级与安全

- WebView 构造抛异常 → `isAvailable == false`，显示原生等待层。
- asset 加载失败、JS 被裁剪或页面不是我们的 asset → `onPageFinished` 用
  `typeof window.applyConnectionState` 探测，失败则 `onFallback` 显示原生等待层。
- 全部面板 API（`begin`/`bind`/`retrying`/`failed`/`cancelled`/`setNight`/`pendingTransport`）
  始终同步驱动 `ConnectionProgressPanel`，且模型由 `CamryConnectionScreen` 单一创建，
  降级时不会丢失尝试次数与计时。
- 没有 `addJavascriptInterface`；禁用 content access、file-from-file、universal access；
  `shouldOverrideUrlLoading` 只放行 `file:///android_asset/carplay-connection.html`，
  其余 scheme / 网络导航全部拦截。内联 WebP 车辆线稿不经过导航回调。
- 车机 GPU 较弱：`motion` 默认 false（分段条流光与过渡关闭）。需要动效时调用
  `connectionScreen.setMotionEnabled(true)`。
- 只覆盖 `shouldOverrideUrlLoading(WebView, String)`：该重载在全部 API level 都会被调用，
  可避免 minSdk 23 上的 `NewApi` lint 失败。

## 已验证 / 未验证

已验证：内嵌 JS `node --check`；`R.string`（32 个）与枚举 / Snapshot 字段对照原面板无缺失；
Kotlin 括号与常量静态检查；Edge headless 注入 6 态、深浅主题、动作桥接全通过
（`A_BACK=back | A_DOCK=nav-settings | A_RETRY=retry | A_RESET=reset | A_FEEDBACK=feedback`）；
1280×720 嵌入截图铺满、无预览控件、卡片完整。

未验证：**本机没有 JDK / Android SDK，`./gradlew --version` 报
`JAVA_HOME is not set`，因此未做 Gradle 编译、lint 与实车测试。** 接入后请在有 SDK 的环境执行：

```sh
./gradlew :common:lintDebug :common:assembleDebug
```

实车重点检查：等待层是否全屏无黑边、浅/深主题随车辆大灯切换、失败时重试 / 重置 / 反馈
按钮是否出现且可点、Dock 的主页 / 设置能否跳转、首帧后等待层是否照常隐藏、
低配主机上的帧率与内存。
