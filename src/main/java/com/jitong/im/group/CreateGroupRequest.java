package com.jitong.im.group;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record CreateGroupRequest(
        @NotBlank
        @Size(max = 128)
        String name,
        @Size(max = 1000)
        String description,
        @NotNull
        String visibility
) {
}
