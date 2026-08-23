package com.jitong.im.platform.error;

import com.jitong.im.platform.observability.RequestContextFilter;
import com.jitong.im.auth.ExpiredAccessTokenException;
import com.jitong.im.auth.DeviceReplacementRequiredException;
import com.jitong.im.auth.DeviceReplacementResponse;
import com.jitong.im.auth.InvalidCredentialsException;
import com.jitong.im.auth.RateLimitExceededException;
import com.jitong.im.auth.RefreshTokenException;
import com.jitong.im.auth.UserRetirementException;
import com.jitong.im.auth.UserRetirementResult;
import com.jitong.im.contact.ContactException;
import com.jitong.im.message.MessageException;
import com.jitong.im.media.MediaException;
import com.jitong.im.group.GroupException;
import com.jitong.im.sync.SyncException;
import com.jitong.im.ai.AiException;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiErrorResponse> mediaTooLarge(HttpServletRequest request) {
        return response(ApiErrorDefinition.MEDIA_TOO_LARGE, request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiErrorResponse> invalidCredentials(HttpServletRequest request) {
        return response(ApiErrorDefinition.AUTH_INVALID, request);
    }

    @ExceptionHandler(ExpiredAccessTokenException.class)
    ResponseEntity<ApiErrorResponse> expiredAccessToken(HttpServletRequest request) {
        return response(ApiErrorDefinition.TOKEN_EXPIRED, request);
    }

    @ExceptionHandler(DeviceReplacementRequiredException.class)
    ResponseEntity<?> replacementRequired(
            DeviceReplacementRequiredException exception,
            HttpServletRequest request
    ) {
        String requestId = RequestContextFilter.requestId(request);
        return ResponseEntity.status(ApiErrorDefinition.DEVICE_REPLACEMENT_REQUIRED.status())
                .body(new DeviceReplacementResponse(
                        1,
                        ApiErrorDefinition.DEVICE_REPLACEMENT_REQUIRED.code(),
                        ApiErrorDefinition.DEVICE_REPLACEMENT_REQUIRED.message(),
                        requestId,
                        java.time.Instant.now(),
                        exception.challenge(),
                        exception.deviceClass().name()));
    }

    @ExceptionHandler(RefreshTokenException.class)
    ResponseEntity<ApiErrorResponse> refreshTokenFailure(HttpServletRequest request) {
        return response(ApiErrorDefinition.AUTH_INVALID, request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ApiErrorResponse> rateLimited(HttpServletRequest request) {
        return response(ApiErrorDefinition.RATE_LIMITED, request);
    }

    @ExceptionHandler(UserRetirementException.class)
    ResponseEntity<ApiErrorResponse> userRetirementConflict(
            UserRetirementException exception,
            HttpServletRequest request
    ) {
        ApiErrorDefinition definition = exception.result() == UserRetirementResult.NOT_FOUND
                ? ApiErrorDefinition.USER_NOT_FOUND
                : ApiErrorDefinition.CONFLICT;
        return response(definition, request);
    }

    @ExceptionHandler(ContactException.class)
    ResponseEntity<ApiErrorResponse> contactFailure(
            ContactException exception,
            HttpServletRequest request
    ) {
        return response(exception.definition(), request);
    }

    @ExceptionHandler(MessageException.class)
    ResponseEntity<ApiErrorResponse> messageFailure(
            MessageException exception,
            HttpServletRequest request
    ) {
        return response(exception.definition(), request);
    }

    @ExceptionHandler(MediaException.class)
    ResponseEntity<ApiErrorResponse> mediaFailure(
            MediaException exception,
            HttpServletRequest request
    ) {
        return response(exception.definition(), request);
    }

    @ExceptionHandler(GroupException.class)
    ResponseEntity<ApiErrorResponse> groupFailure(
            GroupException exception,
            HttpServletRequest request
    ) {
        return response(exception.definition(), request);
    }

    @ExceptionHandler(SyncException.class)
    ResponseEntity<ApiErrorResponse> syncFailure(
            SyncException exception,
            HttpServletRequest request
    ) {
        return response(exception.definition(), request);
    }

    @ExceptionHandler(AiException.class)
    ResponseEntity<ApiErrorResponse> aiFailure(
            AiException exception,
            HttpServletRequest request
    ) {
        return response(exception.definition(), request);
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
