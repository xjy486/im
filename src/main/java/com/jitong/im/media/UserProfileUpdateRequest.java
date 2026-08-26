package com.jitong.im.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record UserProfileUpdateRequest(
        @NotBlank
        @Size(max = 128)
        String displayName
) {
}
