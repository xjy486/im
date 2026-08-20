package com.jitong.im.message;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

record MessageSendRequest(
        @NotNull UUID clientMsgId,
        String type,
        String text,
        UUID mediaId
) {
}
