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

    static Map<String, String> contactRequestData() {
        return Map.of(
                "version", "1",
                "type", "CONTACT_REQUEST");
    }

    static Map<String, String> groupInviteData() {
        return Map.of(
                "version", "1",
                "type", "GROUP_INVITE");
    }

    static Map<String, String> profileChangedData() {
        return Map.of(
                "version", "1",
                "type", "PROFILE_CHANGED");
    }

}
