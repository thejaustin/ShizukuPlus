# Dhizuku 模式（Device Owner 集成）

Dhizuku 模式是 Shizuku+ 的重要架构增强之一。

原版 Shizuku 的系统权限依赖 `shizuku_server` 进程，该进程必须通过 Root 或无线 ADB 启动。无线 ADB 可能被系统关闭，例如断开 Wi-Fi 后，需要手动重新启动。

Shizuku+ 将 Dhizuku 的核心思路集成到自身架构中，可以把 Shizuku+ 管理器设为 Device Owner。这样应用可以通过 `DevicePolicyManager` 获得更持久的权限锚点。

## 工作原理

当 Shizuku+ 成为 Device Owner 后，它可以访问强大的 `DevicePolicyManager` 系统服务。Shizuku+ 会作为代理，把 DPM Binder 安全地共享给已经获得 Shizuku 授权的第三方应用。

这种方式可以在无 Root、无持续无线 ADB 的情况下提供更稳定的系统权限来源，并天然跨重启保留。

## 启用 Dhizuku 模式

启用前需要用 ADB 把 Shizuku+ 设为 Device Owner。此步骤只需要执行一次。

1. 用安装了 ADB 的电脑连接设备；如果已有 Root，也可以用本地终端。
2. 临时移除设备上的所有账号，例如 Google、Samsung 等。Android 要求设置 Device Owner 前设备上不能有账号。
3. 按安装的构建运行对应命令。两个构建的接收器类名都是 `af.shizuku.manager.admin.DhizukuAdminReceiver`，区别只在包名：

```bash
# Shizuku+ 标准构建
adb shell dpm set-device-owner af.shizuku.plus.api/af.shizuku.manager.admin.DhizukuAdminReceiver

# Shizuku+ Drop-In 构建
adb shell dpm set-device-owner moe.shizuku.privileged.api/af.shizuku.manager.admin.DhizukuAdminReceiver
```

请不要把接收器缩写成 `.../.admin.DhizukuAdminReceiver`。该类位于 `af.shizuku.manager` 命名空间，而不是应用包名下。

如果失败并提示已有账号，说明第 2 步没有完成。移除所有账号后重新执行。

4. 成功后可以重新添加账号，Dhizuku 模式会在 Shizuku+ 中保持可用。

## 安全与委托

Shizuku+ 会安全管理 DPM 委托。请求 DPM Binder 的应用仍然必须走标准 Shizuku 授权流程。只有用户明确授权的应用才能使用该桥接，不能绕过授权滥用 Device Owner 权限。
