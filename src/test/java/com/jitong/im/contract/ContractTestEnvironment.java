package com.jitong.im.contract;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class ContractTestEnvironment {

    private static final String MINIO_ACCESS_KEY = "contract-test-access";
    private static final String MINIO_SECRET_KEY = "contract-test-secret-key";
    private static final String MINIO_BUCKET = "contract-test-media";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = ContractDependencies.postgres(
            "contract-test-database-password");

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
                MINIO_BUCKET);
    }
}
