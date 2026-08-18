package com.jitong.im.media;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class MediaBucketProvisioner implements ApplicationRunner {

    private final MinioClient minioClient;
    private final MediaProperties properties;

    MediaBucketProvisioner(MinioClient minioClient, MediaProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        BucketExistsArgs bucket = BucketExistsArgs.builder()
                .bucket(properties.bucket())
                .build();
        if (!minioClient.bucketExists(bucket)) {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(properties.bucket())
                    .build());
        }
    }
}
