package com.jitong.im.media;

import java.time.Instant;

public interface MediaStorage {

    void put(String objectKey, byte[] content, String contentType);

    StoredMedia get(String objectKey);

    void delete(String objectKey);

    Iterable<StoredObject> list(String prefix);

    record StoredMedia(
            java.io.InputStream content,
            long contentLength,
            String contentType
    ) {
    }

    record StoredObject(
            String objectKey,
            Instant lastModified
    ) {
    }
}
