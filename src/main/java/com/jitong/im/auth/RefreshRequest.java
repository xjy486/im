package com.jitong.im.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record RefreshRequest(
        @NotBlank
        @Size(max = 256)
        String refreshToken
) {
}
