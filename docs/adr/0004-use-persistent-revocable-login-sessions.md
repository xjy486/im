---
status: accepted
---

# 使用可撤销的持久登录会话

用户以不可变公开账号 `account_no` 和密码登录，昵称不唯一且不能登录。密码使用 Argon2id；登录后签发短期高熵不透明 Access Token 和按 family 轮换的 Refresh Token。服务端只保存 SHA-256 摘要，不使用 JWT；登录会话归属于具体设备，Token 不存入消息数据库。

`auth_sessions` 保存 Access Token 摘要，`refresh_tokens` 保存 family、父子轮换和状态。Refresh Token 重放会撤销整个 family 和对应设备。刷新在客户端保持单航班，成功后重建 WSS；App 重启可以通过仍有效的设备 Refresh Token 保持登录。

