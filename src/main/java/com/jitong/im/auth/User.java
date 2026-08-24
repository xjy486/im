package com.jitong.im.auth;

import java.util.UUID;

record User(
        UUID id,
        String accountNo,
        String displayName,
        String passwordHash,
        boolean passwordMustChange,
        boolean temporaryPasswordUsed
) {
    User(UUID id, String accountNo, String displayName, String passwordHash) {
        this(id, accountNo, displayName, passwordHash, false, false);
    }
}
