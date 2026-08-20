package com.jitong.im.push;

public interface FcmSender {

    FcmDeliveryResult sendNewMessage(String token);
}
