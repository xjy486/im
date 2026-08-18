---
status: accepted
---

# 使用版本化 JSON 实时协议

稳定错误码至少包括 `AUTH_INVALID`、`TOKEN_EXPIRED`、`DEVICE_REPLACEMENT_REQUIRED`、`NOT_CONTACT`、`NOT_MEMBER`、`FORBIDDEN_ROLE`、`IDEMPOTENCY_CONFLICT`、`RECALL_WINDOW_EXPIRED`、`SYNC_RESET_REQUIRED`、`MEDIA_EXPIRED`、`AI_BUSY`、`AI_BUDGET_EXCEEDED`、`CONTEXT_CHANGED` 和 `RATE_LIMITED`。REST 以 OpenAPI 描述，WSS 信封和 AI 结果以版本化 JSON Schema 描述。

WSS 握手只接受 `Authorization: Bearer`，禁止查询参数令牌；每 30 秒发送标准 Ping，60 秒未收到 Pong 才断开。重连按 1、2、4、8 秒指数退避并加入随机抖动，最大 30 秒；认证撤销不无限重试。访问令牌在到期前约 60 秒通过单航班刷新，刷新后重建连接。业务 JSON 帧限制为 64 KiB，图片只能通过 HTTP 上传。

WSS 命令、响应和事件统一使用包含 `version`、`operation`、`requestId` 和 `body` 的 JSON 信封，服务端同步事件另包含 `syncSeq`。服务端从登录会话取得发送者身份，并对每个操作校验会话权限；服务端实体 ID 使用 UUIDv7，客户端幂等 ID 和请求 ID 使用 UUIDv4，消息排序只依赖会话序号。
