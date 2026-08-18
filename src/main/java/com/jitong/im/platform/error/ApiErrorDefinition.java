package com.jitong.im.platform.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum ApiErrorDefinition {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Request could not be processed"),
    AUTH_INVALID(HttpStatus.UNAUTHORIZED, "Authentication is invalid"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Authentication token has expired"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access is forbidden"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Request method is not supported"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Requested resource was not found"),
    CONFLICT(HttpStatus.CONFLICT, "Request conflicts with current state"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "Requested user was not found"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Request media type is not supported"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Request rate limit was exceeded"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service dependencies are not ready"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "The service could not process the request");

    private final HttpStatus status;
    private final String message;

    ApiErrorDefinition(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return name();
    }

    public String message() {
        return message;
    }

    public static ApiErrorDefinition forStatus(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            return INTERNAL_ERROR;
        }
        return switch (status) {
            case BAD_REQUEST -> INVALID_REQUEST;
            case UNAUTHORIZED -> AUTH_INVALID;
            case FORBIDDEN -> FORBIDDEN;
            case METHOD_NOT_ALLOWED -> METHOD_NOT_ALLOWED;
            case NOT_FOUND -> RESOURCE_NOT_FOUND;
            case CONFLICT -> CONFLICT;
            case UNSUPPORTED_MEDIA_TYPE -> UNSUPPORTED_MEDIA_TYPE;
            case TOO_MANY_REQUESTS -> RATE_LIMITED;
            case SERVICE_UNAVAILABLE -> SERVICE_UNAVAILABLE;
            default -> INTERNAL_ERROR;
        };
    }
}
