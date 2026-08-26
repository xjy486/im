package com.jitong.im.android.ui

import com.jitong.im.android.contact.ConversationSummary
import com.jitong.im.android.contact.messageListPreview
import com.jitong.im.android.group.GroupSummary
import com.jitong.im.android.group.displayText
import java.util.UUID

internal enum class MessageListConversationType {
    C2C,
    GROUP,
}

internal data class MessageListEntry(
    val conversationId: UUID,
    val title: String,
    val preview: String,
    val sortTimestamp: Long,
    val sortSequence: Long,
    val unreadCount: Int,
    val type: MessageListConversationType,
    val conversation: ConversationSummary? = null,
    val group: GroupSummary? = null,
)

internal fun messageListEntry(conversation: ConversationSummary): MessageListEntry =
    MessageListEntry(
        conversationId = conversation.conversationId,
        title = conversation.peerDisplayName,
        preview = conversation.messageListPreview(),
        sortTimestamp = conversation.latestMessage?.sortTimestamp ?: Long.MIN_VALUE,
        sortSequence = conversation.latestMessage?.conversationSeq ?: Long.MIN_VALUE,
        unreadCount = conversation.unreadCount,
        type = MessageListConversationType.C2C,
        conversation = conversation,
    )

internal fun messageListEntry(group: GroupSummary): MessageListEntry =
    MessageListEntry(
        conversationId = group.conversationId,
        title = group.name,
        preview = group.latestMessage?.displayText() ?: "暂无消息",
        sortTimestamp = group.latestMessage?.sortTimestamp ?: Long.MIN_VALUE,
        sortSequence = group.latestMessage?.conversationSeq ?: Long.MIN_VALUE,
        unreadCount = group.unreadCount,
        type = MessageListConversationType.GROUP,
        group = group,
    )

internal fun List<MessageListEntry>.sortedForMessageList(): List<MessageListEntry> =
    sortedWith(
        compareByDescending<MessageListEntry> { it.sortTimestamp }
            .thenByDescending { it.sortSequence }
            .thenBy { it.title.lowercase() }
            .thenBy { it.conversationId.toString() },
    )
