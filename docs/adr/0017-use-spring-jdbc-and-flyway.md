---
status: accepted
---

# 使用 Spring JDBC 和 Flyway 管理持久化

服务端使用 Spring JDBC `JdbcClient` 编写显式 SQL，并用 Flyway 管理 PostgreSQL schema，不采用 JPA/Hibernate。该选择让会话行锁、`RETURNING`、部分唯一索引、事务性 outbox 和精确事务边界能够直接审查和测试。
