package com.jitong.im.ai;

import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

final class AiProviderFailures {

    private AiProviderFailures() {
    }

    static boolean isRetryable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TransientAiException
                    || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            if (current instanceof HttpStatusCodeException httpFailure
                    && retryableStatus(httpFailure.getStatusCode().value())) {
                return true;
            }
            if (current instanceof WebClientResponseException webFailure
                    && retryableStatus(webFailure.getStatusCode().value())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean retryableStatus(int status) {
        return status == 429 || status >= 500 && status <= 599;
    }
}
