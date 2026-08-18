package com.jitong.im.platform.error;

import com.jitong.im.platform.observability.RequestContextFilter;
import com.jitong.im.auth.ExpiredAccessTokenException;
import com.jitong.im.auth.InvalidCredentialsException;
import com.jitong.im.auth.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class
    })
    ResponseEntity<ApiErrorResponse> invalidRequest(HttpServletRequest request) {
        return response(ApiErrorDefinition.INVALID_REQUEST, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> methodNotAllowed(HttpServletRequest request) {
        return response(ApiErrorDefinition.METHOD_NOT_ALLOWED, request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> unsupportedMediaType(HttpServletRequest request) {
        return response(ApiErrorDefinition.UNSUPPORTED_MEDIA_TYPE, request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiErrorResponse> invalidCredentials(HttpServletRequest request) {
        return response(ApiErrorDefinition.AUTH_INVALID, request);
    }

    @ExceptionHandler(ExpiredAccessTokenException.class)
    ResponseEntity<ApiErrorResponse> expiredAccessToken(HttpServletRequest request) {
        return response(ApiErrorDefinition.TOKEN_EXPIRED, request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ApiErrorResponse> rateLimited(HttpServletRequest request) {
        return response(ApiErrorDefinition.RATE_LIMITED, request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> responseStatus(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        ApiErrorDefinition definition = ApiErrorDefinition.forStatus(exception.getStatusCode());
        return response(definition, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> resourceNotFound(HttpServletRequest request) {
        return response(ApiErrorDefinition.RESOURCE_NOT_FOUND, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> internalError(Exception exception, HttpServletRequest request) {
        log.error("api_failure exceptionType={}", exception.getClass().getName());
        return response(ApiErrorDefinition.INTERNAL_ERROR, request);
    }

    private ResponseEntity<ApiErrorResponse> response(
            ApiErrorDefinition definition,
            HttpServletRequest request
    ) {
        ApiErrorResponse body = ApiErrorResponse.create(definition, RequestContextFilter.requestId(request));
        return ResponseEntity.status(definition.status()).body(body);
    }
}
