package com.jitong.im.platform.error;

import com.jitong.im.platform.observability.RequestContextFilter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ApiErrorController implements ErrorController {

    @RequestMapping("${server.error.path:${error.path:/error}}")
    ApiErrorResponse error(HttpServletRequest request, HttpServletResponse response) {
        ApiErrorDefinition definition = ApiErrorDefinition.forStatus(resolveStatus(request));
        response.setStatus(definition.status().value());
        return response(definition, request);
    }

    private HttpStatus resolveStatus(HttpServletRequest request) {
        Object statusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (statusCode instanceof Integer value) {
            HttpStatus status = HttpStatus.resolve(value);
            if (status != null) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private ApiErrorResponse response(ApiErrorDefinition definition, HttpServletRequest request) {
        return ApiErrorResponse.create(definition, RequestContextFilter.requestId(request));
    }
}
