package com.jitong.im.message;

import java.util.List;
import java.util.UUID;

record ConversationReadState(
        UUID conversationId,
        UUID userId,
        long readSeq
) {
}

record ConversationReadStatePage(
        int version,
        UUID conversationId,
        List<ConversationReadState> states
) {
}
