---
status: accepted
---

# 按上海自然日原子预留 AI Token 预算

服务端以 `Asia/Shanghai` 自然日计算每用户预算，`ai_daily_budgets` 保存 `owner_user_id`、`budget_date`、`limit_tokens`、`reserved_tokens`、`used_tokens` 和并发版本。任务开始前通过条件更新原子预留预计最大用量，完成后按实际输入输出用量结算，失败或取消时释放剩余额度；客户端时间不参与预算归属。
