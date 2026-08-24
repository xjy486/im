package com.jitong.im.auth;

import java.util.Set;
import java.util.UUID;

public record AuthCredentialsRevokedEvent(Set<UUID> deviceIds) {
}
