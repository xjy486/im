package com.jitong.im.contract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorContractTest extends ContractTestEnvironment {

    private static final String REQUEST_ID = "5dfb3ac8-4cb8-4e9e-9803-cbe9bd178597";

    @Autowired
    private TestRestTemplate http;

    @Test
    void unknown_resource_uses_the_versioned_error_contract_without_echoing_sensitive_input() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", REQUEST_ID);

        ResponseEntity<Map<String, Object>> response = http.exchange(
                "/api/v1/not-a-resource?token=do-not-echo&mediaUrl=https://media.invalid/private-object",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(REQUEST_ID);
        assertThat(response.getBody()).containsEntry("version", 1);
        assertThat(response.getBody()).containsEntry("code", "RESOURCE_NOT_FOUND");
        assertThat(response.getBody()).containsEntry("message", "Requested resource was not found");
        assertThat(response.getBody()).containsEntry("requestId", REQUEST_ID);
        assertThat(response.getBody()).containsOnlyKeys("version", "code", "message", "requestId", "timestamp");
        assertThat(response.getBody().get("timestamp")).isInstanceOf(String.class);
        assertThat(response.getBody().toString())
                .doesNotContain("do-not-echo", "media.invalid", "private-object");
    }
}
