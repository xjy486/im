package com.jitong.im.auth;

import java.util.Locale;

public enum DeviceClass {
    MOBILE,
    PC;

    static DeviceClass fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return MOBILE;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
