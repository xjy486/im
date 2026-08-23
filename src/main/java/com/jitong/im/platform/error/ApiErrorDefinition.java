package com.jitong.im.platform.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum ApiErrorDefinition {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Request could not be processed"),
    AUTH_INVALID(HttpStatus.UNAUTHORIZED, "Authentication is invalid"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Authentication token has expired"),
    DEVICE_REPLACEMENT_REQUIRED(HttpStatus.CONFLICT, "Device replacement confirmation is required"),
    SYNC_RESET_REQUIRED(HttpStatus.CONFLICT, "The synchronization cursor is outside the retained window"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access is forbidden"),
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN, "The user's group role cannot perform this operation"),
    NOT_MEMBER(HttpStatus.FORBIDDEN, "The user is not an active group member"),
    NOT_CONTACT(HttpStatus.FORBIDDEN, "The conversation is not available"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "The client message identifier is already in use"),
    RECALL_WINDOW_EXPIRED(HttpStatus.CONFLICT, "The message recall window has expired"),
    TEXT_TOO_LONG(HttpStatus.BAD_REQUEST, "Message text is too long"),
    TEXT_TOO_LARGE(HttpStatus.BAD_REQUEST, "Message text is too large"),
    FRAME_TOO_LARGE(HttpStatus.BAD_REQUEST, "Message frame is too large"),
    MEDIA_INVALID(HttpStatus.BAD_REQUEST, "The image could not be safely decoded"),
    MEDIA_DIMENSIONS_TOO_LARGE(HttpStatus.BAD_REQUEST, "The image dimensions are too large"),
    MEDIA_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "The image is too large"),
    MEDIA_FORBIDDEN(HttpStatus.FORBIDDEN, "The media is not available to this user"),
    MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND, "The media was not found"),
    MEDIA_EXPIRED(HttpStatus.GONE, "The media has expired"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Request method is not supported"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Requested resource was not found"),
    CONFLICT(HttpStatus.CONFLICT, "Request conflicts with current state"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "Requested user was not found"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Request media type is not supported"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Request rate limit was exceeded"),
    AI_CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "Both C2C participants must enable the private AI assistant"),
    AI_BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "The user's daily private AI budget is exhausted"),
    AI_BUSY(HttpStatus.TOO_MANY_REQUESTS, "The user's private AI queue is full"),
    AI_NOT_FOUND(HttpStatus.NOT_FOUND, "The private AI resource was not found"),
    AI_EXPIRED(HttpStatus.GONE, "The private AI job expired before it completed"),
    AI_WORKER_LEASE_EXPIRED(HttpStatus.SERVICE_UNAVAILABLE, "The private AI worker lease expired"),
    AI_CONTEXT_CHANGED(HttpStatus.CONFLICT, "The authorized AI context changed before the result was saved"),
    AI_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The AI provider is unavailable"),
    AI_INVALID_RESULT(HttpStatus.BAD_GATEWAY, "The AI provider returned an invalid structured result"),
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
            case PAYLOAD_TOO_LARGE -> MEDIA_TOO_LARGE;
            case GONE -> MEDIA_EXPIRED;
            case CONFLICT -> CONFLICT;
            case UNSUPPORTED_MEDIA_TYPE -> UNSUPPORTED_MEDIA_TYPE;
            case TOO_MANY_REQUESTS -> RATE_LIMITED;
            case SERVICE_UNAVAILABLE -> SERVICE_UNAVAILABLE;
            default -> INTERNAL_ERROR;
        };
    }
}
