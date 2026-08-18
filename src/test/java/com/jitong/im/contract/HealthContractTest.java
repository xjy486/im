package com.jitong.im.contract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Test
    @SuppressWarnings("unchecked")
    void readiness_reports_real_postgresql_and_minio_as_healthy() {
        ResponseEntity<Map> response = http.getForEntity("/api/v1/system/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
        assertThat(response.getBody()).containsEntry("version", 1);
        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        assertThat(components).containsEntry("postgresql", "UP").containsEntry("minio", "UP");
    }
}
