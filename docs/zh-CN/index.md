# Shizuku+ 中文文档

Shizuku+ 是基于 Shizuku 的增强版本，保持对现有 Shizuku 应用的兼容，同时加入更可靠的连接流程、Watchdog、Plus API、Root 兼容桥、Dhizuku 模式等能力。

这里是本项目的中文帮助入口。应用内的“帮助”“报告问题”“更新说明”等链接会优先指向这些文档和本项目的 GitHub 页面。

## 核心文档

- [服务连接与启动流程](service-connection.md)：无线 ADB、电脑 ADB、Root 启动、错误说明、后台自动启动和 Watchdog。
- [知识库与故障排查](knowledgebase.md)：常见连接问题、Watchdog 通知、应用管理、设备兼容性。
- [权限说明](permissions.md)：Shizuku+ 请求的权限、用途，以及哪些能力依赖这些权限。
- [自动化应用控制 Shizuku+](automation-apps.md)：Tasker、MacroDroid 等 Locale 兼容应用如何启动/停止服务。
- [Dhizuku 模式](dhizuku-mode.md)：通过 Device Owner 提供更持久的系统权限锚点。
- [AICore+ 自动化桥](aicore-plus-bridge.md)：面向授权应用的无 Root 辅助功能代理。
- [Root 兼容中心](root-compatibility-hub.md)：为只支持 `su` 的应用提供 Shizuku+ 权限桥接。
- [Shizuku 与 Shizuku+ 对比](../comparison_shizuku_vs_plus.md)：架构差异与 Plus 能力说明。

## Shizuku+ 有什么不同

- 通用权限提供器：统一 Root、ADB Shell、Device Owner 等权限来源。
- 更可靠的连接引擎：自动记忆端口、重试、错误提示和连接进度。
- Shell 拦截与 Plus API：把常见 `pm`、`am`、`settings` 命令路由到更快的原生接口。
- Overlay Manager Plus：面向 Android 14+ / One UI 8+ 的主题与覆盖管理增强。
- Service Doctor / Watchdog：检测服务崩溃并自动按退避策略重启，同时记录到活动日志。

## 问题反馈

请优先阅读 [知识库与故障排查](knowledgebase.md)。如果问题仍然存在，请到本项目的 [Issues](https://github.com/qianyumeng0228/ShizukuPlus/issues) 提交反馈，或使用应用内“报告错误”功能发送邮件到 `support@xiaoyuanqiang.xyz`。
