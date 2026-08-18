---
status: accepted
---

# 使用非 Token 流式的私人异步 AI 结果

AI Worker 异步处理任务，通过请求用户的同步流发送 `AI_JOB_QUEUED`、`AI_JOB_STARTED`、`AI_JOB_COMPLETED` 和 `AI_JOB_FAILED`，MOBILE 与 PC 各自消费。首版不转发模型 Token 流，完成后一次性读取结构化结果，以避免断线重组、部分缓存和多设备流式一致性复杂度。
