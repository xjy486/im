---
status: accepted
---

# 支持公开搜索、链接二维码和私密邀请入群

公开群搜索使用 PostgreSQL `pg_trgm` 对规范化名称和简介建立 GIN 索引，只返回 `PUBLIC + ACTIVE` 群，并采用游标分页及用户/IP限速。搜索结果可以包含用于发起入群申请的公开群号，但不暴露会话 ID 或群主信息。

群可见性分为 `PUBLIC`、`UNLISTED` 和 `PRIVATE`：公开群可搜索并支持链接/二维码，非公开群可通过完整群号或链接/二维码发现，私密群只能由管理者邀请。用户主动加入均需群主或管理员审批，管理员按账号发出的成员邀请也必须由被邀请人确认后才能入群。邀请令牌使用 256 位随机值，服务端只保存 SHA-256 摘要，默认七天有效并支持次数上限和撤销；二维码只编码 HTTPS Deep Link。公开搜索返回群名称、群号、头像、简介和成员数量。

入群申请具有 `PENDING`、`APPROVED`、`REJECTED`、`CANCELLED`、`EXPIRED` 状态。账号成员邀请具有 `PENDING`、`ACCEPTED`、`REJECTED`、`CANCELLED`、`EXPIRED` 状态。普通移除后允许再次申请；只有群黑名单会同时阻止搜索申请、链接申请和管理员邀请。
