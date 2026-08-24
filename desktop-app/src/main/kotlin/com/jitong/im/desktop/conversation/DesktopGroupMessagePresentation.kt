package com.jitong.im.desktop.conversation

import com.jitong.im.desktop.local.LocalMessage

internal fun LocalMessage.groupMessageText(): String =
    when {
        type == "SYSTEM" -> {
            val event = when (systemEventType) {
                "GROUP_CREATED" -> "Group created"
                "MEMBER_JOINED" -> "Member joined"
                "MEMBER_LEFT" -> "Member left"
                "MEMBER_REMOVED" -> "Member removed"
                "ROLE_CHANGED" -> "Member role changed"
                "GROUP_PROFILE_UPDATED" -> "Group profile updated"
                "AI_POLICY_CHANGED" -> "Group AI policy updated"
                "GROUP_DISSOLVED" -> "Group dissolved"
                else -> "Group system event: ${systemEventType ?: "UNKNOWN"}"
            }
            buildString {
                append(event)
                systemRole?.let {
                    append(", current role: ")
                    append(it)
                }
            }
        }
        state == "MODERATED" -> {
            if (moderatedReason.isNullOrBlank()) {
                "Message removed by a group moderator"
            } else {
                "Message removed by a group moderator: $moderatedReason"
            }
        }
        state == "RECALLED" -> "Message recalled"
        else -> text
    }
