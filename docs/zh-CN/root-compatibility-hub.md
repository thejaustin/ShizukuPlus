# Root 兼容中心（SU Bridge）

有些应用只知道通过 `su` 二进制请求权限，并不了解 Shizuku。Root 兼容中心，也就是 SU Bridge，会把这类旧式 `su` 调用桥接到 Shizuku+ 的高权限服务。

Shizuku+ 会导出一个小型 `su` 包装脚本。你把目标应用的“自定义 su 路径”指向这个脚本，目标应用就可以在设备无 Root 的情况下获得类似 Root 的高权限能力。

## 它是什么，不是什么

- 它适用于支持“自定义 su 路径”或“自定义二进制路径”的应用，例如部分文件管理器、备份工具等。
- 它不是 Storage Bridge。Storage Bridge 提供特权文件访问，不会满足应用的 Root 检测。
- 如果应用硬编码 `/system/bin/su` 且不允许改路径，一般无法通过 SU Bridge 服务。

## 前置条件

1. 先在主页导出 Shizuku 文件。该操作会把 `su` 包装脚本写到你选择的文件夹。
2. Shizuku+ 服务需要处于可用状态，Root、ADB/无线调试或 Dhizuku 模式均可。

## 手动设置

1. 在主页导出 Shizuku 文件。
2. 打开 Root 兼容中心，点击“复制路径”，复制导出的 `su` 脚本完整路径。
3. 在目标应用中找到“SU Path”“Binary Path”“Custom su”等设置项，粘贴该路径。
4. 让目标应用重新检测 Root，通常需要关闭再打开 Root 选项或重启应用。

目标应用必须先在 Shizuku+ 中获得授权。桥接会以调用应用自己的身份认证，如果该应用没有 Shizuku+ 权限，请先在授权应用列表中允许它。

如果仍无法检测到 Root，可能是目标应用尝试直接执行共享存储中的 `su` 文件，而该位置通常以 `noexec` 挂载。此时需要目标应用支持通过 `sh` 调用路径，或把脚本放到可执行位置。

## 自动设置（仅 Root 模式）

如果 Shizuku+ 运行在 Root 模式，Root 兼容中心会显示可识别的应用，并提供 Magic Setup / Setup All 之类的自动配置入口。该自动化需要 Root；在 ADB/无线调试或 Dhizuku 模式下，请使用手动设置。

## 每个应用的注意事项

不同应用的设置名称和位置不同。请在目标应用设置里查找“SU Path”“Binary Path”“Custom su binary”“Root -> advanced”等类似选项。

常见示例：

- ZArchiver：设置中启用自定义 su 路径，然后粘贴导出路径。
- FV File Explorer：Root 设置中填写 custom su 路径。

## 限制

- 不支持无自定义路径、硬编码系统 `su` 的应用。
- 在 ADB 模式下桥接提供的是 shell/ADB 权限，不是真正的 uid 0 Root；确实需要 uid 0 的命令仍可能失败。
- 如果应用原生支持 Shizuku，优先使用原生 Shizuku 支持，通常更稳定。
