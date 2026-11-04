# 耗电统计 · Battery Monitor

> AI SLOP WARNING.

**把 Scene 的「耗电统计」单独拆出来做的轻量 App。**
实时功率、逐应用耗电、电量曲线，数据全部来自系统自己的电池统计——不上传、不联网。

| 主界面 | 折叠标题 | 说明提示 | 历史记录 |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/01-main.png" width="200" /> | <img src="docs/screenshots/02-collapsed.png" width="200" /> | <img src="docs/screenshots/03-legend.png" width="200" /> | <img src="docs/screenshots/04-history.png" width="200" /> |

## 功能一览

- **使用过程曲线**：亮屏（蓝色实线）、熄屏（灰色实线）、漏记（虚线）一眼区分；时间轴随会话时长在 10 分钟到 24 小时之间自动伸缩。
- **图表上的 App 图标**：沿时间轴分列、自下而上堆叠，瓦数越高越靠上；点一下图表即可隐藏 / 显示。
- **逐应用功耗**：每个 App 的前台时长、平均功耗、平均 / 最高温度，支持 W ↔ mA 切换与功耗排序。
- **实时遥测**：满容量、温度、电压、瞬时功率（充电显示正值）。
- **后台守护进程**：通过 Shizuku 以 root 身份独立采样，App 被系统冻结或杀掉也不断记录。
- **会话与历史**：清除统计自动归档（`-x%/h / xW / xWh / 续航`），可单条删除或清空。
- **多语言**：简体中文 / 繁體中文 / English / 日本語。
- **轻量**：release APK 约 2.3 MB，只含 arm64-v8a，无网络权限。

## 开始使用

1. 先装好并激活 **Shizuku**（或 Sui / KernelSU 这类能提供 root shell 的方案）。
2. 安装 APK（见下方「安装」），打开应用。
3. 首次启动会请求 Shizuku 授权，允许后即可看到数据。
4. 正常使用手机即可，App 会在后台持续记录。

### 安装

从 [Actions](https://github.com/Shikochin/BatteryMonitor/actions) 最新一次构建的 Artifacts，或从 Releases 下载 `BatteryMonitor-1.0-build*.apk`：

```bash
adb install -r BatteryMonitor-1.0-build*-arm64-v8a.apk
```

本项目只做侧载：release 包使用 debug 签名，不上架应用商店。

**系统要求**：Android 10+（minSdk 29），64 位 ARM 设备；Shizuku 权限必需，root 可选。

## 界面说明

| 区块 | 说明 |
| --- | --- |
| 使用过程 | 电量曲线 + 曲线上的 App 图标；右上角显示当前电量，`?` 打开线型图例 |
| 遥测行 | 满容量（Wh，按健康度折算）· 温度（°C）· 电压（v）· 实时功率 |
| 统计行 | 平均功耗 · 已使用 · 理论续航 |
| 使用场景 | 全部应用（不截断）：前台时长、平均功耗、平均 / 最高温度；左侧按钮切 W ↔ mA，右侧按钮切功耗升 / 降序 |
| 顶栏 | 大标题随滚动折叠为小标题；历史记录、清除本次统计（删除前二次确认） |
| 下拉刷新 | 整页下拉即可重新读取统计 |
| 历史记录 / 说明提示 | 清除统计后自动归档的会话，以及图表线型说明 |

## 数据从哪来

- **Shizuku（必需）**：通过 Shizuku 读取系统自己的电池数据——root 模式直读 sysfs，只有 shell 权限时读 `dumpsys`；逐应用统计始终来自 `dumpsys batterystats`。
- **权限**：只申请了 `QUERY_ALL_PACKAGES`（读取应用列表与图标），**没有网络权限**，所有数据留在本机。
- 采样规则、功耗与续航公式、电流单位自校准等实现细节见 [docs/algorithm.md](docs/algorithm.md)。

## 多语言

界面支持简体中文、繁體中文、English、日本語：默认跟随系统语言；Android 13+ 还可以在
「设置 → 应用 → 耗电统计 → 语言」里单独切换，不影响系统语言。

## 构建（开发者）

环境：JDK 17+，Android SDK 37；Gradle 9.8.2（wrapper 已配置，`./gradlew` 即可）。

```bash
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

- 版本号：`versionName` 固定 `1.0`，`versionCode` = git commit 数（`git rev-list --count HEAD`），本地与 CI 一致。
- release：R8 全量优化、只打 arm64-v8a、资源只保留实际用到的语言，APK 约 **2.3 MB**。
- CI：[`.github/workflows/android.yml`](.github/workflows/android.yml) 在 push / PR 时自动构建，并校验 `versionCode`、ABI 与签名后上传 Artifacts；打 `v*` tag 会附带 GitHub Release。

## 技术栈

Kotlin · Jetpack Compose · [Miuix](https://github.com/compose-miuix-ui/miuix)（HyperOS 风格设计系统）· Shizuku API。
状态层用 `StateFlow` + `AndroidViewModel`，没有第三方 DI 或数据库依赖。

## 说明

- 界面与数据口径参考 **Scene 9.4.18** 的「耗电统计」，仅作个人使用，与 Scene 官方无关。
- 本项目由 AI 辅助开发（AI SLOP WARNING）。
