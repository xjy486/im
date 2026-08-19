package com.jitong.im.message;

import com.jitong.im.platform.error.ApiErrorDefinition;

public class MessageException extends RuntimeException {

    private final ApiErrorDefinition definition;

    public MessageException(ApiErrorDefinition definition) {
        super(definition.message());
        this.definition = definition;
    }

    public ApiErrorDefinition definition() {
        return definition;
    }
}
