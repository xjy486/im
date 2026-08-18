---
status: accepted
---

# 增加具备加密本地历史的 Compose Desktop 客户端

Android 仍是主交付物，同时提供面向 macOS 的 Compose Desktop 客户端，展示手机与 PC 同时在线、跨端镜像和设备类别并发规则。PC 支持登录、文本与图片收发、本地历史、中英文搜索、加密媒体缓存和跨重启增量同步，但首版只能在线发送。

PC 使用嵌入式 H2 AES，每个账号一个加密数据库文件，随机文件密钥保存到 macOS Keychain；共享协议、领域模型和分词逻辑，通过独立 JDBC/H2 持久化适配器实现。

