package com.jitong.im.media;

import com.jitong.im.platform.error.ApiErrorDefinition;

public class MediaException extends RuntimeException {

    private final ApiErrorDefinition definition;

    public MediaException(ApiErrorDefinition definition) {
        super(definition.message());
        this.definition = definition;
    }

    public MediaException(ApiErrorDefinition definition, Throwable cause) {
        super(definition.message(), cause);
        this.definition = definition;
    }

    public ApiErrorDefinition definition() {
        return definition;
    }
}
