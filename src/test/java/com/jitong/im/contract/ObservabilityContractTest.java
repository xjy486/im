package com.jitong.im.contract;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jitong.im.platform.observability.RequestContextFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityContractTest extends ContractTestEnvironment {

    private static final String TOKEN = "secret-bearer-token";
    private static final String MESSAGE = "private-message-body";
    private static final String MEDIA_URL = "https://media.invalid/private-object";

    @Autowired
    private TestRestTemplate http;

    @Test
    void logs_and_metrics_expose_operations_without_sensitive_values() {
        Logger requestLogger = (Logger) LoggerFactory.getLogger(RequestContextFilter.class);
        Level originalLevel = requestLogger.getLevel();
        requestLogger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        requestLogger.addAppender(logs);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(TOKEN);
            headers.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"message\":\"" + MESSAGE + "\",\"mediaUrl\":\"" + MEDIA_URL + "\"}";

            http.exchange(
                    "/api/v1/not-a-resource?token=" + TOKEN,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);

            ResponseEntity<String> metricsResponse = http.getForEntity("/actuator/prometheus", String.class);
            String metrics = metricsResponse.getBody();
            String renderedLogs = logs.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + '\n' + right);

            assertThat(metricsResponse.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(metrics)
                    .contains("jitong_wss_connections_active")
                    .contains("jitong_outbox_backlog")
                    .contains("jitong_fcm_failures_total")
                    .contains("jitong_sync_resets_total")
                    .contains("jitong_message_ack_latency_seconds_count")
                    .contains("jitong_ai_queue_depth")
                    .contains("jvm_memory_used_bytes")
                    .doesNotContain(TOKEN, MESSAGE, MEDIA_URL, "private-object");
            assertThat(renderedLogs)
                    .contains("http_request method=POST")
                    .doesNotContain(TOKEN, MESSAGE, MEDIA_URL, "private-object");
        } finally {
            requestLogger.detachAppender(logs);
            requestLogger.setLevel(originalLevel);
            logs.stop();
        }
    }
}
