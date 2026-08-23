package com.jitong.im.auth;

import java.util.UUID;

public interface PrivateAiDataEraser {

    void eraseForRetirement(UUID userId);
}
