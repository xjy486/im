package com.jitong.im.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PushTokenRequest(
        @NotBlank
        @Size(max = 4096)
        String token
) {
}
