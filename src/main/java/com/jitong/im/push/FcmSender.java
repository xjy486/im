package com.jitong.im.push;

public interface FcmSender {

    FcmDeliveryResult sendNewMessage(String token);

    FcmDeliveryResult sendContactRequest(String token);

    FcmDeliveryResult sendGroupInvite(String token);

    FcmDeliveryResult sendProfileChanged(String token);
}
