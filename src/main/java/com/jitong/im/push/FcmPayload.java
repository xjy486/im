package com.jitong.im.push;

import java.util.Map;

final class FcmPayload {

    private FcmPayload() {
    }

    static Map<String, String> newMessageData() {
        return Map.of(
                "version", "1",
                "type", "NEW_MESSAGE");
    }
}
