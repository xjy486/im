---
status: accepted
---

# 固定数据库事务的加锁顺序

设备替换固定按 `user → devices → auth_sessions → refresh_tokens` 加锁；消息事务按 `conversation → message/media → user_sync_counters` 加锁，涉及多个用户计数器时按用户 ID 排序；头像事务按 `user → media → peer user_sync_counters` 加锁。任何路径不得反向获取这些锁，以降低并发事务死锁风险。
