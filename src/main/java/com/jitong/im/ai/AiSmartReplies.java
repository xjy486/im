package com.jitong.im.ai;

import java.util.List;

public record AiSmartReplies(List<Draft> replies) {

    public AiSmartReplies {
        replies = List.copyOf(replies);
    }

    public record Draft(String text, String tone) {
    }
}
