package com.jitong.im.platform.health;

import java.util.Map;

public record ServiceHealthResponse(
        int version,
        String status,
        Map<String, String> components
) {
    static ServiceHealthResponse up(Map<String, String> components) {
        return new ServiceHealthResponse(1, "UP", Map.copyOf(components));
    }
}
