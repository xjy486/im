---
status: accepted
---

# 使用事务性 outbox 原子接受消息

最多 100 人的群消息继续在单个 PostgreSQL 事务中锁定会话、校验活动成员、写入消息、为每名活动成员生成用户同步事件，并为其活动设备生成 outbox；提交后才 ACK。该有界同步扇出换取明确的一致性，不增加 Kafka。

每条 outbox 记录至少包含 `event_type`、`entity_id`、`target_device_id`、`status`、`attempt_count`、`next_attempt_at` 和 `completed_at`，且不复制正文。消息、用户同步事件及目标设备 outbox 在同一事务内写入，Dispatcher 在提交后异步发送；本项目不增加 Kafka 或微服务。

消息发送在一个 PostgreSQL 事务中完成幂等检查、成员校验、会话序号分配、类型化消息写入、媒体绑定、双方同步事件和 outbox 写入，提交后才返回 ACK。WebSocket 与 FCM 分发由独立 Dispatcher 消费 outbox 并重试，绝不在数据库事务内调用外部推送服务；同一会话通过更新 `last_seq` 行串行分配序号。
