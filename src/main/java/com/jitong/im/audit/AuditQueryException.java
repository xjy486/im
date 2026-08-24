package com.jitong.im.audit;

import com.jitong.im.platform.error.ApiErrorDefinition;

public class AuditQueryException extends RuntimeException {

    private final ApiErrorDefinition definition;

    public AuditQueryException(ApiErrorDefinition definition) {
        this.definition = definition;
    }

    public ApiErrorDefinition definition() {
        return definition;
    }
}

