package com.jitong.im.media;

import com.jitong.im.platform.error.ApiErrorDefinition;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.NoSuchElementException;

@Component
class MinioMediaStorage implements MediaStorage {

    private final MinioClient minioClient;
    private final MediaProperties properties;

    MinioMediaStorage(MinioClient minioClient, MediaProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw new MediaException(ApiErrorDefinition.INTERNAL_ERROR, exception);
        }
    }

    @Override
    public StoredMedia get(String objectKey) {
        try {
            var stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
            return new StoredMedia(
                    minioClient.getObject(GetObjectArgs.builder()
                            .bucket(properties.bucket())
                            .object(objectKey)
                            .build()),
                    stat.size(),
                    stat.contentType());
        } catch (Exception exception) {
            throw new MediaException(ApiErrorDefinition.MEDIA_NOT_FOUND, exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new MediaException(ApiErrorDefinition.INTERNAL_ERROR, exception);
        }
    }

    @Override
    public Iterable<StoredObject> list(String prefix) {
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.bucket())
                    .prefix(prefix)
                    .recursive(true)
                    .build());
            return () -> new Iterator<>() {
                private final Iterator<Result<Item>> delegate = results.iterator();
                private StoredObject next;
                private boolean prepared;

                @Override
                public boolean hasNext() {
                    prepare();
                    return next != null;
                }

                @Override
                public StoredObject next() {
                    prepare();
                    if (next == null) {
                        throw new NoSuchElementException();
                    }
                    StoredObject result = next;
                    next = null;
                    prepared = false;
                    return result;
                }

                private void prepare() {
                    if (prepared) {
                        return;
                    }
                    while (delegate.hasNext()) {
                        try {
                            Item item = delegate.next().get();
                            if (!item.isDir() && item.lastModified() != null) {
                                next = new StoredObject(
                                        item.objectName(),
                                        item.lastModified().toInstant());
                                break;
                            }
                        } catch (Exception exception) {
                            throw new MediaException(ApiErrorDefinition.INTERNAL_ERROR, exception);
                        }
                    }
                    prepared = true;
                }
            };
        } catch (Exception exception) {
            throw new MediaException(ApiErrorDefinition.INTERNAL_ERROR, exception);
        }
    }
}
