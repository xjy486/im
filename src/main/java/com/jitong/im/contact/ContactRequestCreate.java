package com.jitong.im.contact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record ContactRequestCreate(
        @NotBlank String accountNo,
        @Size(max = 100) String verification
) {
}
