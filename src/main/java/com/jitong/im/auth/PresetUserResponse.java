package com.jitong.im.auth;

import java.util.UUID;

public record PresetUserResponse(
        int version,
        UUID userId,
        String accountNo,
        String displayName
) {
    static PresetUserResponse from(User user) {
        return new PresetUserResponse(1, user.id(), user.accountNo(), user.displayName());
    }
}
