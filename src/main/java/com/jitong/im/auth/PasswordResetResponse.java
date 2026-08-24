package com.jitong.im.auth;

record PasswordResetResponse(
        int version,
        String temporaryPassword,
        boolean passwordMustChange
) {
}
