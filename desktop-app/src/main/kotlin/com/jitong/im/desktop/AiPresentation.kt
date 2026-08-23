package com.jitong.im.desktop

import com.jitong.im.desktop.local.LocalMessage
import java.util.UUID

internal fun LocalMessage.isEligibleForAiEvidence(): Boolean =
    state == "ACTIVE"
        && localState != "SENDING"
        && type in setOf("TEXT", "IMAGE")
        && runCatching { UUID.fromString(messageId) }.isSuccess
