package com.jitong.im.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank
        @Pattern(regexp = "[1-9][0-9]{10}")
        String accountNo,
        @NotBlank
        @Size(max = 256)
        String password,
        @JsonAlias("deviceType")
        @Pattern(regexp = "MOBILE|PC")
        String deviceClass,
        @JsonAlias({"installId", "installation_id"})
        @Size(max = 256)
        String installationId
) {
}
