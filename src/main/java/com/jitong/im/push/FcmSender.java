package com.jitong.im.push;

import java.util.UUID;

public interface FcmSender {

    FcmDeliveryResult send(String token, String eventType);
}
