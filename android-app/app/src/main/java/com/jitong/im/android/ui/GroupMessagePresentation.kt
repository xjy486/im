package com.jitong.im.android.ui

import com.jitong.im.android.local.LocalMessageEntity

internal fun LocalMessageEntity.groupMessageText(): String =
    when {
        type == "SYSTEM" -> {
            val event = when (systemEventType) {
                "GROUP_CREATED" -> "群聊已创建"
                "MEMBER_JOINED" -> "成员加入群聊"
                "MEMBER_LEFT" -> "成员主动退出群聊"
                "MEMBER_REMOVED" -> "成员已被移出群聊"
                "ROLE_CHANGED" -> "成员角色已变更"
                "GROUP_PROFILE_UPDATED" -> "群资料已更新"
                "AI_POLICY_CHANGED" -> "群 AI 策略已更新"
                "GROUP_DISSOLVED" -> "群聊已解散"
                else -> "群系统事件：${systemEventType ?: "UNKNOWN"}"
            }
            buildString {
                append(event)
                systemRole?.let {
                    append("，当前角色：")
                    append(it)
                }
            }
        }
        state == "MODERATED" -> {
            if (moderatedReason.isNullOrBlank()) {
                "消息已被群主或管理员移除"
            } else {
                "消息已被群主或管理员移除：$moderatedReason"
            }
        }
        state == "RECALLED" -> "消息已撤回"
        else -> text
    }
