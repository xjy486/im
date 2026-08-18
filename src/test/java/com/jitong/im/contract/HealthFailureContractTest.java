package com.jitong.im.contract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class HealthFailureContractTest {

    private static final String MINIO_ACCESS_KEY = "health-failure-access";
    private static final String MINIO_SECRET_KEY = "health-failure-secret-key";
    private static final String REQUEST_ID = "f0d210a5-92f5-4f08-a4aa-6b747bce2a6b";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = ContractDependencies.postgres(
            "health-failure-database-password");

    @Container
    static final GenericContainer<?> MINIO = ContractDependencies.minio(
            MINIO_ACCESS_KEY,
            MINIO_SECRET_KEY);

    @DynamicPropertySource
    static void configureDependencies(DynamicPropertyRegistry registry) {
        ContractDependencies.register(
                registry,
                POSTGRES,
                MINIO,
                MINIO_ACCESS_KEY,
                MINIO_SECRET_KEY,
                "health-failure-media");
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    void dependency_failure_uses_the_versioned_error_contract() {
        MINIO.stop();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", REQUEST_ID);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<Map<String, Object>> response = http.exchange(
                    "/api/v1/system/health",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<>() {
                    });

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).containsEntry("version", 1);
            assertThat(response.getBody()).containsEntry("code", "SERVICE_UNAVAILABLE");
            assertThat(response.getBody()).containsEntry("requestId", REQUEST_ID);
            assertThat(response.getBody().toString())
                    .doesNotContain(MINIO_ACCESS_KEY, MINIO_SECRET_KEY, "minio/health");
        });
    }
}
