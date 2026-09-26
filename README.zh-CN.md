<div align="center">

# Shizuku+

面向 Android 的高级特权进程管理器。

本项目是 [Shizuku](https://github.com/RikkaApps/Shizuku) 的增强版，构建于 [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) 之上，带来易用性改进、向后移植的优化，以及独占的 Plus API。

Shizuku 让普通应用可以借助通过 adb 或 root 启动的特权进程，直接使用系统级 API。Shizuku+ 在保持完全兼容的同时，为进阶用户与开发者增加功能。

[![Stars](https://img.shields.io/github/stars/thejaustin/ShizukuPlus?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/thejaustin/ShizukuPlus/stargazers)
[![Downloads](https://img.shields.io/github/downloads/thejaustin/ShizukuPlus/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/thejaustin/ShizukuPlus/releases)
[![Latest Release](https://img.shields.io/github/v/release/thejaustin/ShizukuPlus?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/thejaustin/ShizukuPlus/releases/latest)

**🌐 语言：** [English](README.md) · **简体中文**

</div>

> **中文翻译说明**：简体中文（`zh-rCN`）本地化由 [Yizutt](https://github.com/Yizutt) 维护。如发现译文问题，或希望参与中文翻译，请到 **[下游仓库 Yizutt/ShizukuPlus](https://github.com/Yizutt/ShizukuPlus)** 提交 issue 或 pull request —— 我们会**每日定时检查反馈与提交**。详见下方「简体中文（zh-rCN）本地化」。

> **欢迎贡献者！** 如果你发现了 bug，或想改进代码库，请提交 issue 或 pull request —— 本项目正在积极寻找贡献者与合作者。

## ⬇️ 下载

从 [GitHub Releases](https://github.com/thejaustin/ShizukuPlus/releases) 获取最新版本 —— 最近的变更内容见该处的发行说明。

## ✨ Shizuku+ 核心特性

*   **统一特权提供者**：为 **Root**、**ADB Shell** 与 **Dhizuku（设备所有者）** 提供同一套接口。
*   **One UI 8+ 主题修复**：让 Hex Installer、Substratum 等主题引擎在 Android 16/17 与 One UI 8+ 上继续可用。
*   **Dhizuku 模式**：把系统设备所有者 binder 共享给任何拥有 Shizuku 权限的应用 —— 通过 ADB 配置，无需 root。
*   **可自定义手势**：左滑、右滑与长按操作，可按应用分别配置。
*   **应用内更新日志**：更新后无需离开应用即可查看新变化。
*   **批量管理**：多选应用，一键授予/撤销权限或将其隐藏。
*   **活动日志**：记录 API 调用与 `su` 桥接命令的审计轨迹，带应用图标并实时刷新。
*   **Root 兼容中心**：面向旧版 root 应用的仪表盘，提供细粒度的模块控制（Magisk 伪装、自动授予、文件拦截器等）。
*   **通用 SU 自动化**：一键「魔法配置」，把所有已安装的 root 应用指向 Shizuku+ SU 桥接。
*   **服务医生**：诊断并修复服务启动问题（含三星 Auto Blocker）。
*   **内置功能指南**：每一项 Plus 功能都带信息图标，用通俗语言说明其作用。
*   **快捷设置磁贴**：直接在通知面板中查看并切换服务状态。

## 🚀 Plus API 特性

Shizuku+ 为高级自动化与工具提供独占的系统接口 —— 以下功能在原版 Shizuku 中均不存在：

*   **AICore+ 自动化桥接**：面向 AI 驱动工具的特权 UI 自动化（界面层级转储、点击/滑动）—— 无需 root。（[已加入](https://github.com/thejaustin/ShizukuPlus/commit/e9bd1187)）
*   **AVF（虚拟机）管理器**：运行带 GPU 加速的隔离 Linux/Microdroid 虚拟机。（[已加入](https://github.com/thejaustin/ShizukuPlus/commit/c8e962f6)）
*   **特权存储代理**：为备份与文件管理提供对受限路径的已认证访问。ADB 模式下 `/data/app/` 与外部 `/Android/data/` 可用；`/data/data/` 需要 root（服务器必须以 UID 0 运行，才能跨越应用私有目录边界）。（[已加入](https://github.com/thejaustin/ShizukuPlus/commit/c8e962f6)）
*   **设备伪造**（设置中的 *伪造设备身份*）：呈现另一套设备身份，以绕过设备专属限制。（[已加入](https://github.com/thejaustin/ShizukuPlus/commit/11867f44)）
*   **智能桥接**（*AI Core Plus*）：特权的 NPU 调度与屏幕上下文智能。（[已加入](https://github.com/thejaustin/ShizukuPlus/commit/e9bd1187)）
*   **窗口管理器 Plus**：强制自由窗口缩放、管理气泡栏（Bubble Bar），以及更稳健的悬浮层。（[已加入](https://github.com/thejaustin/ShizukuPlus/commit/e9bd1187)）
*   **系统主题桥接**（*Overlay Manager Plus*）：免 root 主题化所需的特权 overlay 管理（例如 Hex Installer）。（[已加入](https://github.com/thejaustin/ShizukuPlus/commit/55f6b7c7)）
*   **网络与 DNS 治理**：为免 root 广告拦截器管理私人 DNS 与防火墙路由。（[已加入](https://github.com/thejaustin/ShizukuPlus/commit/55f6b7c7)）
*   **深度进程控制**（*Activity Manager Plus*）：让进程管理器能更强力地结束应用并设置待机分组。（[已加入](https://github.com/thejaustin/ShizukuPlus/commit/55f6b7c7)）
*   **连续性桥接**：在 Shizuku+ 设备之间安全地交接状态与任务。（[已加入](https://github.com/thejaustin/ShizukuPlus/commit/20cf14f7)）

## 🛠️ 向后移植与优化

Shizuku+ 无需任何代码改动，即可让普通 Shizuku 应用运行得更快、兼容性更好：

*   **透明 Shell 拦截器**：把常见的 `pm`、`am`、`settings` 命令改由更快的原生 API 处理。
*   **本地 ADB 代理**：在 15555 端口模拟一个 ADB 服务器，让旧版应用无需保持无线 ADB 开启即可使用 Shizuku。
*   **SU 桥接**：为支持自定义 root 路径的非 root 应用提供由 Shizuku 支撑的 `su` 直替版。在 shell UID 允许的前提下，常见 root 命令会被翻译为真实的框架操作 —— `iptables --uid-owner` 的按应用封禁会变成生效中的 `NetworkPolicy` 限制，`resetprop` 读写真实的系统属性，`chmod`/`chown` 真实生效 —— 只有当某项操作确实需要 root 时（例如刷写分区或加载内核模块），才会回退为模拟成功。
*   **`plus` CLI 助手**：一个特权命令行工具，可在 `rish` 中使用。
*   **动态应用数据库**：从 GitHub 保持界面中的应用描述与建议内容及时更新。

## ⚙️ 模块化控制

Shizuku+ 中的一切都可关闭。使用设置中的 **Plus 功能** 分类来开关：
*   透明 Shell 拦截
*   各项 Plus API（AVF、存储、智能等）
*   主屏幕卡片可见性
*   活动日志记录

## 🔌 第三方应用兼容性

Shizuku+ 以自有包名（`af.shizuku.plus.api`）安装，因此可与原版 Shizuku 共存。由于大多数感知 Shizuku 的应用会专门查找 `moe.shizuku.privileged.api` 这个包名，Shizuku+ 附带了一个轻量的 **Compat Hub（兼容中心）** —— 一个小型配套应用，它注册该包名，并把 binder/权限请求转发给 Shizuku+。

**如果第三方应用检测不到 Shizuku+：**
1. 启动 Shizuku+ 服务（ADB 或 root）。
2. 在主屏幕上，使用 **Compat Hub** 卡片安装配套应用（它已内置在应用内；安装过程经由正在运行的服务完成，因此请先启动服务）。
3. 重新打开该第三方应用 —— 此时它应该能检测到 Shizuku 并收到服务 binder。

另一种做法是安装 **直替版** 构建，它直接以 `moe.shizuku.privileged.api` 注册（请勿与原版 Shizuku 同时安装）。

## ☑️ 系统要求

**最低：Android 7+ · 完整支持到 Android 17（SDK 37）**
- **Root 模式：** 需要已 root 的设备
- **无线调试模式：** Android 11+ 以及所有 Android TV
- **电脑模式：** 所有设备
- **开机自启：** 仅在无线调试或 Root 模式下可用

在 **Android 16+** 上，Shizuku+ 会申请新的本地网络保护（Local Network Protection）权限，以保证无线调试的发现与配对继续可用；在 **Android 17** 上，它会透明地处理隐藏 API `deviceId` 的变更，使已授权应用仍能显示、权限授予仍然生效。

## 📱 开发者指南

有关独占 Plus API 的文档，请见 [Shizuku+-API](https://github.com/thejaustin/ShizukuPlus-API) 仓库。

## 🙏 致谢与许可

Shizuku+ 是一个社区驱动的增强项目，fork 自 [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku)，而后者本身又 fork 自最初的 [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)。本项目与原 RikkaApps 团队无隶属关系。

感谢以下上游贡献者与项目，他们的工作使 Shizuku+ 得以存在：

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** —— 奠定了 Shizuku 项目及其优雅的 API 设计。
- **[thedjchi](https://github.com/thedjchi)** —— 提供了中间 fork 与易用性改进，并承接了 **Android 17（SDK 37）兼容性** 工作，Shizuku+ 的 A17 支持正是由此改编而来。
- **[kerneldroid / Nightzuku](https://github.com/kerneldroid/Nightzuku)** —— Android 17 隐藏 API `deviceId` 兼容方案（`Android17Compat` / `InstalledPackagesCompat` 反射层）以及本地网络保护处理方式的源头，本 fork 的 A17 支持即由此演化而来。
- **[LandonMoran](https://github.com/LandonMoran)** —— 将 Nightzuku 的 Android 17 支持移植进 thedjchi fork，并在**实体 Android 17 设备上端到端验证**（配对、服务启动与已授权应用列表），这正是 Shizuku+ 移植所依据的实机验证。
- **[Muntashir Akon](https://github.com/MuntashirAkon)** —— 提供了 aShell You 代码库，启发了终端与 shell 自动化功能。
- **[iamr0s](https://github.com/iamr0s)** —— 提供了 Dhizuku，实现统一的设备所有者特权模式；以及 AndroidAppProcess，用于独立的 Java 进程执行。
- **[pascua28](https://github.com/pascua28)** —— 提供了三星原生 System UID 1000 提权集成。
- **[kerneldroid](https://github.com/kerneldroid)** —— 提供了 Nightzuku fork，启发了我们在 Android 16/17（SDK 37）隐藏 API 上的韧性处理（应对 `deviceId`）与界面现代化。
- **[ShizukuExt-SystemUID](https://github.com/ShizukuExt)** —— 提出了超越常规限制的系统性 UID 1000 提权构想。

### 上游项目

| 项目 | 作者 | 许可证 | 作用 |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | 奠定特权进程架构 |
| [Shizuku（fork）](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | 带易用性改进的中间 fork；承接了 Shizuku+ 所改编的 Android 17 兼容工作 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Android 17 隐藏 API `deviceId` + 本地网络保护兼容方案的源头 |
| [Shizuku（fork）](https://github.com/pascua28/Shizuku) | pascua28 | Apache 2.0 | 三星 UID 1000 系统执行利用 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Android 16/17 API 韧性与界面现代化 |
| [ShizukuExt-SystemUID](https://github.com/ShizukuExt) | ShizukuExt Team | Apache 2.0 | 系统 UID 提权构想 |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | 设备所有者 binder 共享（Dhizuku 模式） |
| [AndroidAppProcess](https://github.com/iamr0s/AndroidAppProcess) | iamr0s | LGPL-3.0 | 独立的高特权 Java 进程包装 |

### 开源库

| 库 | 作者 | 许可证 |
|---------|--------|---------|
| [AndroidX Jetpack](https://developer.android.com/jetpack) | Google / AOSP | Apache 2.0 |
| [Material Components](https://github.com/material-components/material-components-android) | Google | Apache 2.0 |
| [Material Symbols](https://fonts.google.com/icons) | Google | Apache 2.0 |
| [Kotlin / Coroutines / Serialization](https://github.com/JetBrains/kotlin) | JetBrains | Apache 2.0 |
| [RikkaX Libraries](https://github.com/RikkaApps)（appcompat、material、insets、html、recyclerview、preference、lifecycle、parcelablelist） | Rikka | Apache 2.0 |
| [Hidden API / Refine](https://github.com/RikkaApps/HiddenApiCompat) | Rikka | Apache 2.0 |
| [Mavericks (MvRx)](https://github.com/airbnb/mavericks) | Airbnb | Apache 2.0 |
| [Lottie](https://github.com/airbnb/lottie-android) | Airbnb | Apache 2.0 |
| [Coil](https://github.com/coil-kt/coil) | Coil Contributors | Apache 2.0 |
| [Koin](https://github.com/InsertKoinIO/koin) | Koin Contributors | Apache 2.0 |
| [Timber](https://github.com/JakeWharton/timber) | Jake Wharton | Apache 2.0 |
| [libsu](https://github.com/topjohnwu/libsu) | topjohnwu | Apache 2.0 |
| [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) | LSPosed | Apache 2.0 |
| [libcxx](https://github.com/lsposed/libcxx) | LSPosed / LLVM | Apache 2.0 + LLVM Exception |
| [AppIconLoader](https://github.com/zhanghai/AppIconLoader) | Zhang Hai | Apache 2.0 |
| [BoringSSL (NDK)](https://github.com/vvb2060/ndk-boringssl) | vvb2060 / Google | Apache 2.0 / ISC |
| [Gson](https://github.com/google/gson) | Google | Apache 2.0 |
| [LeakCanary](https://github.com/square/leakcanary) | Square | Apache 2.0 |
| [AboutLibraries](https://github.com/mikepenz/AboutLibraries) | Mike Penz | Apache 2.0 |
| [Bouncy Castle](https://www.bouncycastle.org/) | Legion of Bouncy Castle | MIT |
| [Sentry Android SDK](https://github.com/getsentry/sentry-java) | Sentry | MIT |
| [SQLite (C Recovery API / CLI)](https://sqlite.org/) | D. Richard Hipp / SQLite Consortium | Public Domain |

完整许可证文本与各库详情：[OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) | [NOTICE](NOTICE)

## 📃 许可证

[Apache 2.0](LICENSE)

### 贡献者

感谢每一位为 Shizuku+ 贡献代码、翻译与测试的人：

**代码贡献者**

| 贡献者 | 贡献内容 |
|-------------|--------------|
| [thejaustin](https://github.com/thejaustin) | 项目创始人兼首席维护者 —— 全部核心 Plus 功能、界面/交互与基础设施 |
| [thedjchi](https://github.com/thedjchi) | 中间 fork 基座；Android 17（SDK 37）兼容性奠基工作 |
| [Kevin Doremy](https://github.com/doremylover) | 死代码清理、无用 import 清理、布局与类重构 |
| [Ryfter](https://github.com/Ryfter) | mDNS 超时改进、前台服务子类型重构、通知体验 |
| [vvb2060](https://github.com/vvb2060) | AGP 构建系统更新、LTO 优化、许可证澄清 |
| [Haruue Icymoon](https://github.com/haruue) | 文档与 README 改进 |

**翻译贡献者**

| 语言 | 贡献者 |
|----------|-------------|
| 巴西葡萄牙语 | [odorizzioficial](https://github.com/odorizzioficial) |
| 简体中文 | [Yizutt](https://github.com/Yizutt) |
| 乌克兰语 | [thejaustin](https://github.com/thejaustin) |
| 法语 | [Ryfter](https://github.com/Ryfter)、[T. Clement](https://github.com/thibaultclement) |
| 越南语 | [ThePrimalPea](https://github.com/ThePrimalPea) |
| 菲律宾语 | [IverCoder](https://github.com/IverCoder) |
| 意大利语 | [Dany-coder778](https://github.com/Dany-coder778) |
| 日语 | [MES-mitutti](https://github.com/MES-mitutti) |

*简体中文是覆盖最完整的语言 —— manager 字符串集 100%，且含伴生字符串；乌克兰语（99.9%）与巴西葡萄牙语（99.6%）紧随其后。其余语言覆盖共享核心字符串。社区译文通过 Crowdin 管理 —— 感谢所有译者！*

### 鸣谢
- 特别感谢 **AkayamiShurui42** 主动进行安全研究并提供稳定性补丁（参考：#239）。
- 感谢 **AlexeiCrystal** 发现 MIUI 崩溃 bug，并建议为旧版应用采用 Compat Hub 变通方案（#241、#242）。
- 感谢 **ddnexus** 与 **kai-bash** 指出设备所有者恢复出厂设置的陷阱以及 Google 备份冲突（#237）。
- 感谢 **Kevinco1** 反馈 root 兼容应用检测问题（#243）。
- 感谢 **aragortsantiago6-beep**、**Scoop2389**（Pixel 9a）与 **ConversionRituals**（小米）进行实体机 Android 16/17 测试、提交崩溃报告与日志，推动了 SDK 37 隐藏 API 与本地网络保护的兼容性修复（#317、#323）。
- 感谢 **gmm96** 跨多个构建进行多轮 logcat 调试，定位到 Cached Apps Freezer 的 binder 投递 bug（#371）。
- 感谢 **[odorizzioficial](https://github.com/odorizzioficial)** 提供完整的巴西葡萄牙语翻译（#409），并提交关于三星「休眠应用」看门狗冻结的详细报告（#415）。

## 🇨🇳 简体中文（zh-rCN）本地化

> **想贡献中文？请到下游仓库 👉 [Yizutt/ShizukuPlus](https://github.com/Yizutt/ShizukuPlus) 提交 issue 或 pull request。我们会每日定时检查反馈与提交。**

### 覆盖度

| 指标 | 数值 |
|------|------|
| 已收录字符串 | **2095 / 2095** |
| 覆盖率 | **100.00%** |
| 缺失（缺失译文） | 0 |
| 阻塞（无法翻译） | 0 |
| 警告 | 0 |
| 已本地化文件 | `values-zh-rCN/strings.xml` 等 9 个文件 |

已覆盖的模块目录：

`manager`、`core/ui`、`compat`（兼容桩）、`common`、`app-process`、`database`、`server`、`shell`、`starter`、`api`、`core/common`、`core/data`

### 尚未覆盖的内容

| 项目 | 状态 | 说明 |
|------|------|------|
| 硬编码字符串 | 建议项 1 处 | 界面中仍有 1 处文本直接硬编码在代码里，无法通过资源文件汉化；需上游改动代码，故不在本地化范围内 |
| `translatable="false"` 条目 | 有意不翻译 | 这些条目为调试/内部标识用途，按上游约定不参与本地化 |
| 引用型数组（`@string/`） | 有意不翻译 | 仅引用其它字符串，随其指向的条目一并生效 |
| 枚举型数组值（如 `none` / `open_app` / `DEFAULT`） | 有意不翻译 | 属于程序判定用的取值，翻译会破坏功能 |
| 品牌与技术专名 | 有意保留原文 | 如 **Shizuku+**、**AMOLED+**、**Material You**、**ADB**、**Shizuku+**、**One UI**、**Hex Installer**、**Magisk**、**Dhizuku** 等 |
| README / 文档 | 本文件已提供 | 本中文 README 由下游维护，与上游 `README.md` 内容同步 |

### 术语与规范

中文译文遵循机器可读术语表 `tools/glossary_zh_rCN.json` 与汉化规范 `tools/LOCALIZATION_SPEC_zh-rCN.md`，关键约束：

- **逐条翻译，禁止批量操作**：每一条译文都必须独立留痕（含选择理由、术语比对结果与自校验结果），无法留痕的改动会被质量门禁直接拒绝提交。
- **只汉化界面资源**：仅写入 `*/src/main/res/values-zh-rCN/*.xml`，不触碰 Kotlin/Java/Gradle/Manifest/CI 等文件。
- **保留其它语言**：`values-<其它语言>/`、`values-night/`、`values-v31/` 等一律不改动，只做中文新增。
- **占位符与转义原样保留**：`%1$s`、`%d`、字面 `\n`、CDATA、HTML 标签等不做改译。
- **品牌加号不可丢失**：如 `Shizuku+` 的 `+`。

### 反馈与贡献方式

1. 到 **[Yizutt/ShizukuPlus](https://github.com/Yizutt/ShizukuPlus)** 提交 issue（描述问题界面与位置，最好附截图）。
2. 或直接提 pull request 修改 `values-zh-rCN/*.xml`。
3. 每日定时任务会检查新增反馈与提交，并同步上游英文变更后跟进处理。