---
status: accepted
---

# 加密可搜索的本地数据和媒体缓存

每个账号在每台设备上使用独立加密数据库。Android 使用 Room + SQLCipher，数据库密钥由 Android Keystore 中的不可导出密钥包装，FTS4 保存英文词项和中文二元词组；PC 使用 H2 AES，文件密钥保存于 macOS Keychain，`message_search_terms(term, message_local_id)` 保存相同搜索词项。两端命中后均以原文校验，单汉字查询降级为库内 `LIKE`。

图片缓存使用 AES-GCM，数据库和缓存不得明文落盘。正常退出保留加密数据，明确清除或设备失信时删除密钥、数据库和缓存。撤回先在数据库事务中写入墓碑、清正文和搜索索引，再由事务后的任务删除加密媒体文件。

