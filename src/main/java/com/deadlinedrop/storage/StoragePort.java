package com.deadlinedrop.storage;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Storage port abstraction providing signed URL generation for direct-to-storage transfers
 * as well as direct stream access for inspections and legacy multipart arm A.
 */
public interface StoragePort {

    record SignedUrl(String url, String method, Map<String, String> headers, Instant expiresAt) {
        public SignedUrl {
            headers = Map.copyOf(headers);
        }
    }

    record ObjectInfo(long size, String contentType) {}

    SignedUrl signPut(String objectPath, String contentType, long maxBytes, Duration ttl);

    SignedUrl signGet(String objectPath, String downloadFileName, Duration ttl);

    Optional<ObjectInfo> stat(String objectPath);

    InputStream open(String objectPath);

    void put(String objectPath, String contentType, InputStream in, long size);

    void delete(String objectPath);
}
