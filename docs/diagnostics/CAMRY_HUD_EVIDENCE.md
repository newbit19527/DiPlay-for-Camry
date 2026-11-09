# 凯美瑞 HUD 普通诊断包

手机导航中，接近转向点而 HUD 未显示时，记录故障时间，继续保持连接并尽快导出普通诊断包。无需开启调试跟踪；此改动不改变显示距离策略、导航控制权或 root 租约。

## 导出文件

- `hud/settings.json`：当前 HUD 开关、距离策略、各类距离和提前程度。
- `hud/state.json`：最新导航解析/输出状态、状态年龄、配置适配模式、导航帧数、无效帧、转向类型、数据年龄、显示阈值及不显示原因。`lastOutput` 保留 SDK 调用开始、返回值、成功/失败和限长异常链；卡住时也可看到最近正在调用的步骤。
- `hud/recent.ndjson`：当前进程最近最多 120 秒的状态记录，稳定状态最多每秒一条，关键状态变化立即保留。上限 256 条、512 KiB，繁忙情况下可能提前淘汰。头部提供实际最早/最新记录时间；进程退出后历史不保留，不应当作完整行车记录。
- `hud/firmware.json`：通过已有共享 root 只读诊断通道用内置 SHA-256 读取固定的三个库，记录每个库的实际 SHA-256 与读取状态。没有白名单，不判断固件版本，也不影响启动。
- `system/logcat.txt`：额外保留 `DiPlay-Pioneer-Navi`、`DiPlay-HudFilter` 的日志，仍属于有限的系统缓冲区。

历史不主动记录道路名、坐标或原始 iAP2 数据。异常链最多保留 4096 字符，SDK 异常链截断有标记，导出按 JSON 字段脱敏，保留合法的实际 SHA-256 和完整 JSON 结构；不再对机器可读证据整行截断。

## 哈希结果的含义

`hud/firmware.json` 的 `scope=export_read_only_not_startup_verdict` 仅记录导出时读到的实际哈希与读取状态。

启动过滤器时也会记录同一组哈希：

```text
hud/state.json
  originalMap.filter.firmwareVerification
    scope=startup_hash_evidence
    checkedAtMs
    modules[].path / actualSha256 / status / error
```

结果没有 `passed` 字段，也不存在白名单：所有固件都按受支持处理，读取失败也只影响该条诊断。未启动过或适配关闭时该字段可能为空。导出时检查不会启动注入器或延长租约。

## 定位顺序

1. `settings.enabled` / `hudEnabled`：开关是否关闭。
2. 导航接收/解析帧数、`routeReason`、`routeFrameValid` 和 `inputAgeMs`：是否缺输入、解析无效或过期。
3. `reason=waiting_for_approach`、`policyThresholdMeters`：是否未到显示距离。
4. `reason=oem_navigation_ownership_unconfirmed`、`originalMap.error`、`originalMap.filter.state/error`：控制权、过滤器或 root 请求是否失败。
5. `firmwareVerification`：记录当前固件三个库的实际哈希，用于对照排查；不影响启动。
6. `lastOutput`、`sdkFailures` 和异常链：是否调用 SDK、被 SDK 拒绝或抛异常。

`result=sdk_accepted` 与 `displayVerified=false` 可以同时出现：SDK 接受不等于物理 HUD 已显示，仍需对应用户实际观察时间。

## 系统组件

普通导出会通过 root 只读通道采集 HUD 定向组件：三个仪表库、`/system/bin/MeterServer`、`/system/bin/SettingServer`、TPS 代码及其 `oat` 产物、framework boot 文件、目标进程映射，以及这些 ELF 的 DT_NEEDED 依赖。清单写入 `hud/components.json`，进程 maps 另存为 `hud/process-maps/*.txt`。上限为 256 个文件、单文件 128 MiB、组件总量 256 MiB，超出部分在 manifest 中标记为 `truncated`/`skipped`。原车导航与设置 APK 及其预编译产物、Bluetooth/Blueforce 组件不采集。

`/report-components` 在此基础上额外追加更广的系统组件（`system/component_inventory.txt`、选定的系统库、TPS 包信息等），仍沿用同一带令牌链接和访问保护。两种包独立缓存。
