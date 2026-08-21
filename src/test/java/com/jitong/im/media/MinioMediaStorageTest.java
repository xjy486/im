package com.jitong.im.media;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MinioMediaStorageTest {

    private static final String ACCESS_KEY = "storage-test-access";
    private static final String SECRET_KEY = "storage-test-secret-key";
    private static final String BUCKET = "storage-test-media";
    private static final DockerImageName MINIO_IMAGE = DockerImageName.parse(
            "minio/minio:RELEASE.2025-04-22T22-12-26Z");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(MINIO_IMAGE)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data", "--console-address", ":9001")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live")
                    .forPort(9000)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    private static MinioMediaStorage storage;

    @BeforeAll
    static void setUp() throws Exception {
        MinioClient client = MinioClient.builder()
                .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build();
        client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        storage = new MinioMediaStorage(
                client,
                new MediaProperties(
                        "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000),
                        ACCESS_KEY,
                        SECRET_KEY,
                        BUCKET,
                        Duration.ofHours(24)));
    }

    @Test
    void lists_nested_objects_recursively() {
        storage.put("message-images/one/original.jpg", new byte[]{1}, "image/jpeg");
        storage.put("message-images/one/thumbnail.jpg", new byte[]{2}, "image/jpeg");
        storage.put("avatars/user/one/512.webp", new byte[]{3}, "image/webp");

        List<String> objectKeys = new ArrayList<>();
        for (MediaStorage.StoredObject object : storage.list("message-images/")) {
            objectKeys.add(object.objectKey());
        }

        assertThat(objectKeys)
                .containsExactlyInAnyOrder(
                        "message-images/one/original.jpg",
                        "message-images/one/thumbnail.jpg");
    }
}
