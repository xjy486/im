package com.jitong.im.abuse;

import com.jitong.im.platform.error.ApiErrorDefinition;

public final class AbuseException extends RuntimeException {

    private final ApiErrorDefinition definition;

    AbuseException(ApiErrorDefinition definition) {
        this.definition = definition;
    }

    public ApiErrorDefinition definition() {
        return definition;
    }
}
