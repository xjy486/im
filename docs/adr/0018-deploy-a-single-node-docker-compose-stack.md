---
status: accepted
---

# 部署单节点 Docker Compose 栈

首版在一台云主机上以 Docker Compose 运行 Caddy、Spring Boot、PostgreSQL 和 MinIO，只有 Caddy 暴露公网 HTTPS/WSS，数据库和对象存储只位于内部网络。凭证通过环境或 Docker Secret 注入，PostgreSQL 与 MinIO 分别持久化、加密备份并定期验证恢复；明确接受单机故障，不引入 Kubernetes、微服务或高可用集群。
