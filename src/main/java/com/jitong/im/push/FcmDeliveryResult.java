package com.jitong.im.push;

public enum FcmDeliveryResult {
    SENT,
    NOT_CONFIGURED,
    NO_TOKEN,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE
}
