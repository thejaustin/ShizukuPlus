# AICore+ 自动化桥

AICore+ 自动化桥是 Shizuku+ 的专属能力，用于支持更高级的无 Root UI 自动化和屏幕上下文提取。它通过一个受控代理的 `AccessibilityService` 实现。

## 解决的问题

现代 AI 自动化工具需要理解屏幕内容并执行点击、滑动等操作。传统方案通常依赖：

1. 无障碍服务：用户不愿意给每个新应用单独授权，而且质量差的服务可能造成系统卡顿。
2. Root 权限：用于模拟底层点击或滑动。

## Shizuku+ 的方案

Shizuku+ 提供 `AICorePlusService`，这是一个集中、优化过的无障碍服务。用户启用后，已授权的 Shizuku 应用可以请求该桥代为执行操作，不需要每个应用都单独申请自己的无障碍权限。

### 能力

- `dumpHierarchy()`：导出当前活动窗口的 UI 层级，格式为结构化 XML，比传统 `uiautomator` dump 更快。
- `performTap(x, y)`：在指定坐标模拟点击。
- `performSwipe(startX, startY, endX, endY, durationMs)`：按指定路径和时长模拟滑动。

## 隐私与安全

- 不被动观察：桥接服务主要作为响应者工作，只在授权应用明确请求时导出层级或执行动作。
- 严格授权：只有用户授予 Shizuku 权限的应用才能使用。
- 尊重系统保护：遵循 Android 安全标记，例如 `FLAG_SECURE`，不读取安全输入框中的密码等敏感内容。
