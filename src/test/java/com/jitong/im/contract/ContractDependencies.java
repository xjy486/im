package com.jitong.im.contract;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

final class ContractDependencies {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17-alpine");
    private static final DockerImageName MINIO_IMAGE = DockerImageName.parse(
            "minio/minio:RELEASE.2025-04-22T22-12-26Z");

    private ContractDependencies() {
    }

    static PostgreSQLContainer<?> postgres(String password) {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("jitong")
                .withUsername("jitong")
                .withPassword(password);
    }

    static GenericContainer<?> minio(String accessKey, String secretKey) {
        return new GenericContainer<>(MINIO_IMAGE)
                .withEnv("MINIO_ROOT_USER", accessKey)
                .withEnv("MINIO_ROOT_PASSWORD", secretKey)
                .withCommand("server", "/data", "--console-address", ":9001")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live")
                        .forPort(9000)
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    static void register(
            DynamicPropertyRegistry registry,
            PostgreSQLContainer<?> postgres,
            GenericContainer<?> minio,
            String minioAccessKey,
            String minioSecretKey,
            String bucket
    ) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("jitong.media.endpoint", () -> minioEndpoint(minio));
        registry.add("jitong.media.access-key", () -> minioAccessKey);
        registry.add("jitong.media.secret-key", () -> minioSecretKey);
        registry.add("jitong.media.bucket", () -> bucket);
    }

    static String minioEndpoint(GenericContainer<?> minio) {
        return "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
    }
}
