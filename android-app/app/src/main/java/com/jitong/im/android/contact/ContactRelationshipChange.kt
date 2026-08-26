package com.jitong.im.android.contact

import java.util.UUID

internal data class ContactRelationshipChange(
    val conversationId: UUID,
    val status: String,
    val relationship: String,
)
