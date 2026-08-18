---
status: accepted
---

# 仅在 Android 支持离线发送队列

Room 的 `pending_commands` 是离线命令的权威队列；WorkManager 只负责按网络条件唤醒处理器、调度和失败重试，不能成为命令事实来源。

Android 允许用户离线发送：文本命令写入加密本地队列，图片先规范化并写入加密待上传区，联网后按依赖顺序上传并发送。Compose Desktop 首版只能在线发送；Android 正常退出登录后不会自动恢复未接受消息，用户重新登录后必须明确重试。
