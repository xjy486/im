package com.jitong.im.sync;

import com.jitong.im.platform.error.ApiErrorDefinition;

public class SyncException extends RuntimeException {

    private final ApiErrorDefinition definition;

    public SyncException(ApiErrorDefinition definition) {
        super(definition.message());
        this.definition = definition;
    }

    public ApiErrorDefinition definition() {
        return definition;
    }
}
