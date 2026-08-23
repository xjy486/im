package com.jitong.im.ai;

import com.jitong.im.platform.error.ApiErrorDefinition;

public class AiException extends RuntimeException {

    private final ApiErrorDefinition definition;

    public AiException(ApiErrorDefinition definition) {
        this.definition = definition;
    }

    public AiException(ApiErrorDefinition definition, Throwable cause) {
        super(cause);
        this.definition = definition;
    }

    public ApiErrorDefinition definition() {
        return definition;
    }
}
