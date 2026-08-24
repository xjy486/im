package com.jitong.im.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record PasswordChangeRequest(
        @NotBlank
        @Size(max = 256)
        String currentPassword,
        @NotBlank
        @Size(min = 8, max = 256)
        String newPassword
) {
}
