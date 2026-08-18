---
status: accepted
---

# 限制 AI 任务排队和自动重试

AI 任务状态为 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` 和 `EXPIRED`。同一用户最多一个运行任务；智能回复在繁忙时直接返回 `AI_BUSY`，总结和信息提取最多排队三个，预算耗尽直接拒绝。只有 429、5xx 和超时可以自动重试一次，结构化输出可以修复一次，其他失败不得自动循环重试。
