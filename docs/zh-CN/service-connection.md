# 服务连接与启动流程

本文说明 Shizuku+ 如何启动特权服务、如何处理连接错误，以及后台自动启动和 Watchdog 会为你做什么。

## 通过无线 ADB 启动

在主页点击“启动”后，Shizuku+ 会打开连接终端并执行四个阶段。

### 1. 解析端口

Shizuku+ 按以下优先级查找无线调试端口：

| 优先级 | 来源 | 说明 |
| --- | --- | --- |
| 1 | 系统属性 `adb_tcp_port` | 无线调试已经启用时可能存在 |
| 2 | mDNS 发现 | 设备开启无线调试后会广播端口 |
| 3 | 上次保存的端口 | 最近一次成功连接后保存 |

如果以上来源都不可用，应用会打开发现对话框，等待系统广播无线调试端口。

### 2. 建立 TCP 连接

`AdbClient` 会连接到 `127.0.0.1:<端口>` 并使用 TLS 握手。连接最多重试 8 次，并使用指数退避，以处理端口刚广播但服务尚未就绪的短暂延迟。

如果启用了 TCP 模式，并且当前端口与保存的 TCP 端口不同，Shizuku+ 会先连接当前端口，再发送 `tcpip:<保存端口>` 让 ADB 切换到持久 TCP 端口。

### 3. 启动服务

连接成功后，Shizuku+ 通过 ADB shell 执行 `libshizuku.so --apk=<路径>`，启动特权的 `shizuku_server` 进程。

### 4. 等待 Binder

启动命令返回后，Shizuku+ 最多等待 15 秒让服务 Binder 注册。如果超时，会静默重试一次，也就是最多等待 30 秒。仍失败时才显示错误对话框。

成功后，终端会显示连接耗时，例如 `Connected in 1.3s`，随后自动关闭并返回主页。

## 连接终端

| 元素 | 行为 |
| --- | --- |
| 进度条 | 连接时显示不确定进度，出错时隐藏 |
| 取消 | 任意阶段立即关闭终端 |
| 已用时间 | 服务确认运行前记录 `Connected in X.Xs` |
| 重试 | 错误对话框中显示，清空终端并重新开始连接 |
| 自动关闭 | Binder 确认注册后立刻关闭，无额外等待 |

## 错误参考

| 错误 | 含义 | 处理方式 |
| --- | --- | --- |
| 无法连接到端口 | ADB 端口不可达，常见原因是防火墙、VPN、广告拦截器阻止本地回环连接 | 在网络过滤应用里允许 Shizuku+ |
| 服务未及时启动 | 服务进程已启动，但 30 秒内没有收到 Binder | 确认无线调试仍开启，然后点击“重试” |
| 请先配对 | 设备要求先完成无线调试配对 | 到开发者选项完成配对流程 |
| 无法生成密钥 | 设备 KeyStore 异常 | 可能需要 OEM 修复或恢复出厂设置 |
| SSL 握手失败 | ADB 密钥损坏或不匹配 | 在开发者选项撤销 USB 调试授权，然后重新配对 |
| Root 未授权 | Root shell 不可用 | 在 Root 管理器里授予 Shizuku+ Root |
| 未知错误 | 未分类异常 | 查看终端日志并重试 |

## 通过电脑 ADB 启动

如果设备不能使用无线 ADB，或者你希望从电脑启动，可以运行一次性的 `adb` 命令。设备重启后需要再次执行。

1. 在电脑安装 Android platform-tools。
2. 开启 USB 调试：设置 -> 关于手机 -> 连续点击版本号 7 次解锁开发者选项，然后在开发者选项开启 USB 调试。
3. 用 USB 连接设备，并在手机上允许 USB 调试。也可以使用 `adb connect <设备 IP>:<端口>` 通过 Wi-Fi 连接。
4. 打开 Shizuku+ 的“通过 ADB 启动”卡片，点击“查看命令”，复制应用给出的完整命令。
5. 在电脑终端运行：`adb shell <你复制的路径>`。

如果 `adb devices` 看不到手机，通常是没有确认 USB 调试弹窗，或数据线/接口不支持数据传输。如果命令执行后应用没有反应，请参考上面的 [错误参考](#错误参考)。

## 通过 Root 启动

当上次启动方式是 Root 时，Shizuku+ 会跳过 ADB：

1. 通过 `libsu` 获取 Root shell。
2. 以 Root 身份运行 `libshizuku.so --apk=<路径>`。
3. 使用与 ADB 路径相同的 Binder 等待逻辑。

Root 路径同样支持进度提示、取消和重试。

## 后台自动启动（开机与 Watchdog）

### 开机启动

`BootCompleteReceiver` 会响应 `BOOT_COMPLETED`、`LOCKED_BOOT_COMPLETED`、`MY_PACKAGE_REPLACED` 以及部分厂商的快速开机广播。

它会读取上次启动方式：

- ADB：通过 WorkManager 排队执行 `AdbStartWorker`，可根据设置等待 Wi-Fi。
- Root：直接执行 Root 启动流程。

### AdbStartWorker 端口解析

后台 worker 使用与手动启动相同的端口解析方式，并额外优先尝试“上次保存的端口”。如果设备重启后端口稳定，后台启动会明显更快。

```
1. 系统属性端口 -> 立即连接
2. 上次保存端口 -> 立即连接
3. mDNS 发现 -> 最多等待 15 秒
```

### Watchdog

启用 Watchdog 后，`WatchdogService` 会以前台服务运行并监听 `ShizukuStateMachine`。当状态变为 `CRASHED` 时：

- 按指数退避等待冷却时间，从 5 秒开始，最高 5 分钟。
- 调用 `ShizukuReceiverStarter.start()` 重新启动服务。
- 服务确认运行后重置崩溃计数。
- 发送崩溃通知，并提供了解详情或关闭提醒的入口。

Shizuku+ 使用三层恢复机制：

| 层级 | 间隔 | 能处理的问题 |
| --- | --- | --- |
| `WatchdogService` | 持续监听 | 普通服务崩溃后的快速恢复 |
| `WatchdogWorker` | 每 2 小时 | 系统只杀死 Watchdog 服务但保留应用进程 |
| `WatchdogAlarmReceiver` | 每 15 分钟 | 整个应用进程被厂商后台管理冻结或杀死 |

第三层通过系统调度的 `AlarmManager` 冷启动应用进程，专门缓解三星 One UI“休眠应用”等会冻结整个进程的场景。这需要精确闹钟权限；如果未授予，Shizuku+ 会退回到非精确唤醒。

这是一种缓解，不是万能修复。如果厂商后台管理非常激进，仍建议把 Shizuku+ 从省电、休眠或后台限制列表中排除。

## 活动日志

每次服务启动和 Watchdog 重启都会记录到“设置 -> 活动日志”。

| 事件 | 日志 |
| --- | --- |
| 手动 ADB 启动 | `Service started via ADB on port <N>` |
| 手动 Root 启动 | `Service started via root` |
| 后台 ADB worker 启动 | `Service started via background ADB worker on port <N>` |
| Watchdog 重启 | `Watchdog: restarting after crash #<N>` |

## 端口记忆

每次 ADB 成功连接后，Shizuku+ 会把端口保存到 SharedPreferences。该端口会用于主页启动、后台启动和 mDNS 发现对话框的快速回退。只要连接成功，保存的端口就会更新为最近可用的值。
