package com.jitong.im.auth;

public class DeviceReplacementRequiredException extends RuntimeException {

    private final String challenge;
    private final DeviceClass deviceClass;

    DeviceReplacementRequiredException(String challenge, DeviceClass deviceClass) {
        this.challenge = challenge;
        this.deviceClass = deviceClass;
    }

    public String challenge() {
        return challenge;
    }

    public DeviceClass deviceClass() {
        return deviceClass;
    }
}
