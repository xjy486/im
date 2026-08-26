package com.jitong.im.android.ui

import com.jitong.im.android.contact.ConversationPreview
import com.jitong.im.android.contact.ConversationSummary
import com.jitong.im.android.group.GroupPreview
import com.jitong.im.android.group.GroupSummary
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageListModelTest {

    @Test
    fun c2c_and_group_entries_are_sorted_by_latest_message() {
        val direct = messageListEntry(
            ConversationSummary(
                version = 1,
                conversationId = UUID.randomUUID(),
                peerUserId = UUID.randomUUID(),
                peerAccountNo = "12345678901",
                peerDisplayName = "Alice",
                status = "ACTIVE",
                relationship = "ACTIVE",
                blockedByMe = false,
                latestMessage = ConversationPreview(
                    conversationSeq = 2,
                    type = "TEXT",
                    text = "来自 Alice",
                    state = "ACTIVE",
                    serverAcceptedAt = "2026-08-26T00:00:02Z",
                ),
            ),
        )
        val group = messageListEntry(
            GroupSummary(
                version = 1,
                conversationId = UUID.randomUUID(),
                groupNo = "22345678902",
                name = "项目群",
                description = "",
                visibility = "PRIVATE",
                role = "MEMBER",
                avatarUrl = null,
                avatarVersion = 0,
                memberCount = 3,
                latestMessage = GroupPreview(
                    conversationSeq = 3,
                    type = "TEXT",
                    text = "群里有新消息",
                    state = "ACTIVE",
                    serverAcceptedAt = "2026-08-26T00:00:03Z",
                ),
            ),
        )

        assertEquals(
            listOf(group.conversationId, direct.conversationId),
            listOf(direct, group).sortedForMessageList().map { it.conversationId },
        )
        assertEquals("群里有新消息", group.preview)
    }
}
