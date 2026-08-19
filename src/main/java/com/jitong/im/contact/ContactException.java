package com.jitong.im.contact;

import com.jitong.im.platform.error.ApiErrorDefinition;

public final class ContactException extends RuntimeException {

    private final ApiErrorDefinition definition;

    ContactException(ApiErrorDefinition definition) {
        this.definition = definition;
    }

    public ApiErrorDefinition definition() {
        return definition;
    }
}
