package com.jitong.im.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderFailuresTest {

    @Test
    void retries_rate_limits_server_failures_and_timeouts() {
        assertThat(AiProviderFailures.isRetryable(httpClientFailure(HttpStatus.TOO_MANY_REQUESTS))).isTrue();
        assertThat(AiProviderFailures.isRetryable(httpServerFailure(HttpStatus.INTERNAL_SERVER_ERROR))).isTrue();
        assertThat(AiProviderFailures.isRetryable(httpServerFailure(HttpStatus.SERVICE_UNAVAILABLE))).isTrue();
        assertThat(AiProviderFailures.isRetryable(new SocketTimeoutException("socket timeout"))).isTrue();
        assertThat(AiProviderFailures.isRetryable(new HttpTimeoutException("HTTP timeout"))).isTrue();
        assertThat(AiProviderFailures.isRetryable(new TimeoutException("operation timeout"))).isTrue();
    }

    @Test
    void does_not_retry_other_failures() {
        assertThat(AiProviderFailures.isRetryable(httpClientFailure(HttpStatus.BAD_REQUEST))).isFalse();
        assertThat(AiProviderFailures.isRetryable(new IllegalArgumentException("invalid request"))).isFalse();
    }

    private HttpClientErrorException httpClientFailure(HttpStatus status) {
        return HttpClientErrorException.create(
                status,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);
    }

    private HttpServerErrorException httpServerFailure(HttpStatus status) {
        return HttpServerErrorException.create(
                status,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);
    }
}
