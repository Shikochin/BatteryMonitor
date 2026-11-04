# 耗电统计 · Battery Monitor

把 Scene 的「耗电统计」页面单独拆出来做成的独立 App，界面用 **Jetpack Compose + [Miuix](https://github.com/compose-miuix-ui/miuix)**（HyperOS 风格设计系统）重新实现。

参考设计里底部的「在寻找其它功能吗?」一栏按需求**不做**。

| 主界面 | 折叠标题 | 说明提示 | 历史记录 |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/01-main.png" width="200" /> | <img src="docs/screenshots/02-collapsed.png" width="200" /> | <img src="docs/screenshots/03-legend.png" width="200" /> | <img src="docs/screenshots/04-history.png" width="200" /> |

## 功能

| 区块 | 内容 |
| --- | --- |
| 使用过程 | 45 分钟电量曲线：屏幕点亮（蓝色实线）/ 屏幕关闭（灰色实线）/ 未记录（蓝或灰虚线），左侧 100%–20% 刻度，底部时间刻度 |
| 遥测行 | 电池满容量（Wh，按健康度折算）· 温度（°C）· 电压（v）· 实时功率（放电 `-x.xxW` / 充电 `x.xxW`） |
| 统计行 | 平均功耗 · 已使用 · 理论续航 |
| 使用场景 | 全部应用（不截断）：前台时长、平均功耗、平均/最高温度；左键切换 W↔mA，右键切换功耗升/降序 |
| 顶栏 | 大标题随滚动折叠为小标题（无返回键）；历史记录、清除本次统计（删除键有二次确认） |
| 历史记录 | 每次清除统计会存档一条：`-x%/h / xW / xWh / xh`，可单条删除或清空 |
| 说明提示 | 图表虚线含义的说明弹窗（对齐参考图） |

## 数据来源

**只走 Shizuku / Sui**：启动即请求权限，拿到 shell 身份后直接读系统的账本，不再用 `UsageStatsManager` 估算。

| 数据 | 来源 |
| --- | --- |
| 电量 / 电压 / 温度 / 充电计数器 | `dumpsys battery`（每 3 s） |
| 平均功耗、已使用 | `dumpsys batterystats --charged`：`Discharge(mAh) × V ÷ Time on battery` |
| 每应用功耗 | 每个 UID 的 mAh × V ÷ 前台时长；时长取自 `--history` 的 `+fg/-fg` 事件 |
| 使用过程曲线 | 启动时用 `batterystats --charged --history` 回填最近 45 分钟，之后按 3 s 采样追加 |
| 满容量 Wh | 系统学到的电池容量 × 3.87 V 标称电压（含健康度衰减） |

界面底部显示当前状态：已授权 → `数据来源：Shizuku · 系统电池统计`；服务在但没授权 → 可点「需要 Shizuku 权限，点击授权」；没检测到服务 → `未检测到 Shizuku / Sui 服务`。

> 本机实测（OnePlus PLZ110 / Android 17 / 7500 mAh）：ColorOS 不提供 `current now`，公共 API 只能给出估算；经 Sui 授权后平均功耗 1.0–2.1 W、每应用功耗与 Scene 同口径（哔哩哔哩 2.7 W 对 2.69 W）。

## 权限

- **Shizuku / Sui**：必需。安装并激活 Shizuku（或使用 Sui 这类 root 模块）后，应用启动会自动弹出授权请求。

## 构建

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

环境：JDK 17+（本机 Gradle 9.6 / JDK 25 可直接构建），Android SDK 37，minSdk 29。

## 技术栈

- Kotlin，AGP 9 内置 Kotlin 支持（无需单独应用 Kotlin 插件）
- Compose BOM `2026.09.00`
- Miuix `0.9.4`（`miuix-ui` + `miuix-icons`）
- Shizuku API `13.1.5`（`api` + `provider`）
- 状态：`StateFlow` + `AndroidViewModel`，无第三方 DI / 数据库依赖

## 结构

```
app/src/main/java/dev/shiko/batterymonitor/
├── MainActivity.kt              # 入口，权限跳转
├── PowerStatsViewModel.kt       # 仅做生命周期桥接
├── model/Models.kt              # BatterySnapshot / ChartSample / UsageScenario / PowerUiState …
├── data/
│   ├── PowerStatsRepository.kt  # 聚合状态，两条数据通道的统一出口
│   ├── BatterySampler.kt        # 广播 + 定时采样
│   ├── UsageStatsProvider.kt    # UsageStatsManager + 图标/标签
│   ├── ShizukuBridge.kt         # Shizuku 服务检测、授权、exec
│   ├── SystemBatteryStatsSource.kt # dumpsys battery / batterystats 解析
│   ├── PowerMath.kt             # 功耗、续航、平滑等纯计算
│   └── SessionStore.kt          # SharedPreferences 持久化
├── ui/
│   ├── PowerStatsScreen.kt      # Scaffold + 折叠顶栏 + 卡片编排
│   ├── theme/Theme.kt           # MiuixTheme 明暗配色
│   └── component/
│       ├── UsageProcessCard.kt  # 使用过程卡片
│       ├── UsageChart.kt        # Canvas 曲线
│       ├── TelemetryIcons.kt    # 手绘电池/温度/电压/插头图标
│       ├── ChartLegendDialog.kt # 说明提示弹窗
│       ├── StatSummaryCard.kt   # 平均功耗/已使用/理论续航
│       ├── UsageScenarioCard.kt # 使用场景列表
│       └── SessionHistoryDialog.kt
└── util/Format.kt               # 时长/功率/温度/电压格式化
```

## 与参考图的差异

- 顶栏改为可折叠大标题（按需求），并去掉返回按钮。
- 配色使用 Miuix 的 token（卡片 `surfaceContainer` #242424，页面 `surface` 纯黑），与参考图的 #0A0A0A 卡片略有不同。
- 字号整体比参考图大约 1–2sp。
- 遥测图标为本项目手绘的矢量图（参考图的图形在 Miuix 图标集中不存在）。
