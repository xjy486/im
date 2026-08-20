package com.jitong.im.media;

interface MediaStorage {

    void put(String objectKey, byte[] content, String contentType);

    StoredMedia get(String objectKey);

    void delete(String objectKey);

    record StoredMedia(
            java.io.InputStream content,
            long contentLength,
            String contentType
    ) {
    }
}
