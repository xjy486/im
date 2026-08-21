package com.jitong.im.group;

import com.jitong.im.platform.error.ApiErrorDefinition;

public final class GroupException extends RuntimeException {

    private final ApiErrorDefinition definition;

    public GroupException(ApiErrorDefinition definition) {
        this.definition = definition;
    }

    public ApiErrorDefinition definition() {
        return definition;
    }
}
