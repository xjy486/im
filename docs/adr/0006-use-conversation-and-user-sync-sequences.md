---
status: accepted
---

# 同时使用会话序号和用户同步序号

每条消息拥有会话内单调递增的 `conversationSeq`，用于排序和发现会话内缺口；每个用户拥有单调递增的 `syncSeq` 事件流，用于新会话、跨端消息、已读、撤回、群事件和私人 AI 结果。`user_sync_counters` 分配用户序号，`user_sync_events` 保存事件，MOBILE 与 PC 在 `device_sync_states` 中分别确认 `last_acked_seq`。

首次同步采用高水位屏障：WSS 认证后返回 B，客户端分页同步 `(lastSyncSeq, B]` 并缓冲实时事件；应用至 B 后，按序处理大于 B 的缓冲事件、去重并发送 `sync.ack`。同步事件保留 30 天；游标过旧返回 `SYNC_RESET_REQUIRED`，客户端通过分页会话和权威历史全量恢复。同步响应按实体 ID 批量物化当前状态，不要求客户端逐事件查询。

