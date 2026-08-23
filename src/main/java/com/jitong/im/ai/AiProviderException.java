package com.jitong.im.ai;

public class AiProviderException extends RuntimeException {

    private final String code;

    public AiProviderException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AiProviderException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
