package com.jitong.im.auth;

public class UserRetirementException extends RuntimeException {

    private final UserRetirementResult result;

    public UserRetirementException(UserRetirementResult result) {
        super(result.name());
        this.result = result;
    }

    public UserRetirementResult result() {
        return result;
    }
}
