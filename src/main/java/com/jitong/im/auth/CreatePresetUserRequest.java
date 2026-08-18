package com.jitong.im.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePresetUserRequest(
        @NotBlank
        @Size(max = 128)
        String displayName,
        @NotBlank
        @Size(min = 8, max = 256)
        String password
) {
}
