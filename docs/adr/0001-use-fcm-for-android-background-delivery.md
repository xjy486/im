---
status: accepted
---

# 使用 FCM 实现 Android 后台送达

Android 前台实时通信使用 WSS；App 处于后台或进程不可用时，使用 FCM 发送不含正文、AI 结果或媒体地址的通用提示。通知类型限定为 `NEW_MESSAGE`、`CONTACT_REQUEST`、`GROUP_JOIN_REQUEST`、`GROUP_INVITE`、`AI_JOB_COMPLETED`、`SECURITY_ALERT` 和 `PROFILE_CHANGED`，其中 `PROFILE_CHANGED` 表示用户或群资料（包括头像）发生变化，客户端收到后通过认证同步补拉资料，不能把推送内容当作资料真相。服务端权威数据始终通过认证同步取得。

Dispatcher 对存在可用前台 WSS 的设备只发送 WebSocket；没有连接或写入失败时才向 MOBILE 发送 FCM。每个 MOBILE 保存一个应用层加密的当前 FCM Token，轮换后通过认证接口更新；永久无效只清空推送 Token，不撤销设备或登录会话。PC 不使用 FCM。
