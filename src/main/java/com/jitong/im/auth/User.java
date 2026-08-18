package com.jitong.im.auth;

import java.util.UUID;

record User(
        UUID id,
        String accountNo,
        String displayName,
        String passwordHash
) {
}
