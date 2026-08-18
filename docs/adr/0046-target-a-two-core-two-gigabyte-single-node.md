---
status: accepted
---

# 以二核二 GB 单节点作为首版运行目标

单机同时运行 Caddy、Spring Boot、PostgreSQL 和 MinIO，目标调整为 100 个并发 WSS、持续 20 条消息/秒和短时 50 条/秒；C2C 服务端持久化 ACK p95 不超过 500ms，100 人群 ACK p95 不超过 1.5s，同步每页最多 200 个事件。客户端十万条文本的中英文搜索 p95 仍以 200ms 为目标，AI 外部调用超时 60 秒但不承诺供应商延迟。部署限制 JVM 堆、数据库连接池和 PostgreSQL 内存，并配置小容量 swap 作为故障缓冲而非正常内存。
