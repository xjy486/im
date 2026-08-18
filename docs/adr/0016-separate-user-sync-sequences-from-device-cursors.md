---
status: accepted
---

# 分离用户同步序号和设备消费游标

每个用户只有一条单调递增的同步事件流，由 `user_sync_counters` 分配序号并写入 `user_sync_events`；每台活动设备在 `device_sync_states` 中独立确认 `last_acked_seq`。因此手机同步不会推进 PC 的消费位置，同时跨端事件仍共享同一用户顺序。
