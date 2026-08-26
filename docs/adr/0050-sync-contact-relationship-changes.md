---
status: accepted
---

# 通过用户同步流传播联系人关系变化

联系人删除和单向拉黑会改变双方对同一个 C2C 会话的发送权限，但双方仍保留只读历史。
因此关系变化不能只更新 `contacts` 和 `conversations`，必须在同一个事务中为双方写入
`CONTACT_RELATIONSHIP_CHANGED` 用户同步事件，并为活动设备写入 outbox。

事件的 `entity_id` 和 `conversation_id` 都指向 C2C 会话，不携带操作者、原因或拉黑方向。
WSS 使用 `contact.relationship.changed` 作为低延迟通知；客户端收到通知后重新拉取权威
会话摘要并更新本地状态。无 WSS 的 MOBILE 设备收到无内容的 `PROFILE_CHANGED` FCM 提示后，
通过 `/sync` 补拉。服务端每次发送命令仍重新校验联系人关系，客户端灰态只属于用户体验，
不属于安全边界。

联系人申请同样不能只依赖联系人页主动刷新。创建申请的事务同时为接收方写入
`CONTACT_REQUEST_CREATED` 用户同步事件和 outbox；前台 WSS 使用
`contact.request.created`，离线 MOBILE 使用 `CONTACT_REQUEST` FCM 提示，客户端收到后
通过认证接口补拉申请列表。
