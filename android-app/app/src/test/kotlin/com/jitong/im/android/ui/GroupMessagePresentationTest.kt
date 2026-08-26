package com.jitong.im.android.ui

import com.jitong.im.android.local.LocalMessageEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupMessagePresentationTest {
    @Test
    fun renders_governance_system_events_with_context() {
        val message = message(
            type = "SYSTEM",
            systemEventType = "ROLE_CHANGED",
            systemTargetUserId = "member-1",
            systemRole = "ADMIN",
        )

        assertEquals(
            "成员角色已变更，当前角色：ADMIN",
            message.groupMessageText(),
        )
    }

    @Test
    fun renders_moderated_messages_as_tombstones_without_text() {
        val message = message(
            state = "MODERATED",
            text = "secret content",
            moderatedReason = "spam",
        )

        assertEquals(
            "消息已被群主或管理员移除：spam",
            message.groupMessageText(),
        )
    }

    @Test
    fun renders_contact_established_as_the_success_message() {
        val message = message(
            type = "SYSTEM",
            systemEventType = "CONTACT_ESTABLISHED",
        )

        assertEquals(
            "你们已经成功加上好友了",
            message.groupMessageText(),
        )
    }

    private fun message(
        type: String = "TEXT",
        state: String = "ACTIVE",
        text: String = "",
        systemEventType: String? = null,
        systemTargetUserId: String? = null,
        systemRole: String? = null,
        moderatedReason: String? = null,
    ) = LocalMessageEntity(
        messageId = "message-1",
        conversationId = "conversation-1",
        senderId = "sender-1",
        clientMsgId = "client-1",
        conversationSeq = 1,
        type = type,
        state = state,
        localState = "RECEIVED",
        text = text,
        serverAcceptedAt = "2026-08-24T00:00:00Z",
        systemEventType = systemEventType,
        systemTargetUserId = systemTargetUserId,
        systemRole = systemRole,
        moderatedReason = moderatedReason,
        createdAt = 1,
    )
}
