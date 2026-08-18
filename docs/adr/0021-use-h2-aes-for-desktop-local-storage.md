---
status: accepted
---

# 使用 H2 AES 保存 PC 本地消息

macOS Compose Desktop 使用 H2 AES 文件数据库，而不采用未加密的 Room KMP 或商业 SQLCipher JDBC。PC 与 Android 共享协议、领域模型和中英文分词逻辑，但通过独立 JDBC/H2 持久化适配器实现同等的本地消息、会话、搜索词项、媒体缓存和设备同步状态；正常退出保留加密数据，明确清除或设备失信时删除 Keychain 密钥、数据库和缓存。
