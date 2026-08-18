---
status: accepted
---

# AI 任务和结果由请求用户私有拥有

`ai_jobs` 至少保存 `owner_user_id`、`requesting_device_id`、`status`、`request_id`、`model`、`prompt_version`、`created_at`、`started_at`、`finished_at`、`error_code` 和 `expires_at`，并以 `UNIQUE(owner_user_id, request_id)` 保证请求幂等。所有 artifact 和 action item 必须绑定 `owner_user_id`，MOBILE 与 PC 通过各自设备游标消费同一用户的私人 AI 同步事件，不能仅凭会话成员权限读取结果。

回复建议默认保留 10 分钟、总结默认保留 30 天、待办保留至完成或删除；用户可以主动删除。账号注销时清理全部 AI 内容，使用记录只允许保留不含会话内容的必要聚合信息。
