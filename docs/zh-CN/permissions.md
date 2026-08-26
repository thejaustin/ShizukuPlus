# Shizuku+ 权限说明

Shizuku+ 请求的权限比原版 Shizuku 更多，因为它提供了更多功能。下面每一项都对应实际功能；如果你不需要某个功能，可以在“设置 -> Shizuku+ 功能”中关闭对应能力。

权限不应该靠盲目信任来授予。如果你发现某个权限没有在这里说明，请到本项目 [Issues](https://github.com/qianyumeng0228/ShizukuPlus/issues/new/choose) 反馈。

## 运行时权限

### 通知（`POST_NOTIFICATIONS`）

用于显示“Shizuku+ 正在运行”状态通知、Watchdog 崩溃提醒、更新下载进度通知。拒绝此权限不会阻止服务运行，但你看不到状态和崩溃通知。

### 附近的 Wi-Fi 设备（`NEARBY_WIFI_DEVICES`）

Android 要求应用拥有此权限才能通过 mDNS 自动发现无线调试端口。Shizuku+ 声明了 `neverForLocation`，不会通过此权限读取物理位置，只用于本地网络服务发现。

### 本地网络保护（`ACCESS_LOCAL_NETWORK`、`USE_LOOPBACK_INTERFACE`）

Android 16+ 会把本地回环连接（`127.0.0.1`）和本地 mDNS 发现放到权限门槛之后。没有这些权限时，无线调试发现和 ADB 配对/连接握手无法访问设备本机端口。

### 忽略电池优化（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）

只用于请求系统弹窗，让你选择是否把 Shizuku+ 排除出 Doze/App Standby 限制。拒绝后服务仍可运行，但更容易被后台管理杀死；Watchdog 可以缓解这一点。

### 安装未知应用（`REQUEST_INSTALL_PACKAGES`）

用于应用内更新器直接安装下载的 APK，也用于 Compat Hub 伴随应用安装器，避免每次都跳到额外安装流程。

### 精确闹钟（`SCHEDULE_EXACT_ALARM`）

用于 Watchdog 的外部重新唤醒层。即使三星“休眠应用”或类似厂商后台管理冻结了整个应用进程，系统调度的闹钟仍可能冷启动 Shizuku+ 检查并恢复服务。如果你拒绝或系统限制此权限，Shizuku+ 会自动退回到非精确唤醒。

## 安装时权限

这些权限不会弹出授权对话框，Android 会在安装时自动授予。它们仍然列在这里，便于你理解用途。

| 权限 | 用途 |
| --- | --- |
| `RECEIVE_BOOT_COMPLETED` | 开启“开机启动”后，在设备重启后恢复服务和 Watchdog。 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、`FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ 要求前台服务声明类型，分别覆盖 Watchdog、ADB 配对和后台同步等工作。 |
| `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE` | GitHub Releases 更新检查，以及无线调试发现所需的 mDNS/组播网络能力。 |
| `QUERY_ALL_PACKAGES` | 用于列出已安装应用，支持活动日志、应用管理和权限授权界面。 |
| `DOWNLOAD_WITHOUT_NOTIFICATION` | 让应用内更新器管理自己的下载进度通知，而不是使用 DownloadManager 默认通知。 |

## 特权/受保护权限

这些权限不是直接向用户请求的，而是 Shizuku+ 通过 ADB、Root 或 Dhizuku 模式启动的特权进程为自己使用。它们正是 Shizuku 类项目存在的原因：让普通应用在用户授权后访问原本需要 Root 或系统权限的 API。

### `WRITE_SECURE_SETTINGS`

核心特权权限。特权进程运行后，你授权的应用可以通过 Shizuku+ API 修改系统设置、管理应用权限等。每个应用都需要你明确授权，不会静默开放。

### `PACKAGE_USAGE_STATS`

用于 Root 兼容中心和部分深度进程控制能力，例如识别近期没有获得 Root/Shizuku 授权的应用。它是受保护权限，必须通过特权进程授予或配置 appops 后才能使用。

### `af.shizuku.plus.permission.MANAGER`

Shizuku+ 管理器应用和自己的特权服务进程之间的私有通道。它是签名级权限，只有使用同一签名的 Shizuku+ 自身能够使用，其他应用不能伪装成管理器。

### `moe.shizuku.manager.permission.API_V23`

兼容原版 Shizuku API 的权限。许多旧应用仍以原版命名空间接入 Shizuku，该权限让它们可以透明地与 Shizuku+ 通信。

## Shizuku+ 不会请求什么

Shizuku+ 不请求 `ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`，不请求联系人、短信、通话记录等个人数据权限，也不使用广告 ID。除崩溃报告外没有额外分析 SDK。未来如果新增高敏感权限，应先在本文说明清楚。
