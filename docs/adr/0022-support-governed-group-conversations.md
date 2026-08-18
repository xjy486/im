---
status: accepted
---

# 支持具有角色治理的群聊会话

群聊使用 `groups`、`conversation_members`、`group_membership_audit`、`group_invites`、`group_join_requests` 和 `group_bans`。群成员角色为唯一 `OWNER`、可选多个 `ADMIN` 和普通 `MEMBER`；群主负责转让、管理员和解散，管理员只能管理普通成员和群资料。

成员当前记录保存角色、状态、`history_visible_after_seq`、`read_seq` 和 `membership_version`。入群时以当前 `conversation.last_seq` 作为历史边界，成员只能读取更大序号；重新入群生成新版本，不能恢复以前历史。退出、被移除或群解散后立即失去访问并清理本地群数据。

群创建、成员与角色变化、资料变化、AI 策略和解散使用不可搜索、不可撤回的 `SYSTEM` 消息进入统一会话序列。发送者可在一分钟内撤回自己的消息，群主和管理员可以随时用 `MODERATED` 墓碑移除违规消息。群已读只维护每个用户的 `read_seq`，首版不展示逐成员已读列表。

