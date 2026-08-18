package com.jitong.im.auth;

import java.util.UUID;

record DeviceSession(
        UUID deviceId,
        UUID userId,
        DeviceClass deviceClass
) {
}
