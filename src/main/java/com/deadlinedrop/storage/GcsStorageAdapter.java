package com.deadlinedrop.storage;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.SignUrlOption;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Google Cloud Storage implementation of {@link StoragePort} using V4 signed URLs.
 * <p>
 * On Cloud Run there is no key file; the client library signs through the IAM Credentials
 * signBlob API using the runtime service account.
 */
@Component
@Profile("cloud")
public class GcsStorageAdapter implements StoragePort {

    private final StorageProperties properties;
    private final Clock clock;
    private final Storage storage;

    public GcsStorageAdapter(StorageProperties properties, Clock clock, Storage storage) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
    }

    @Override
    public SignedUrl signPut(String objectPath, String contentType, long maxBytes, Duration ttl) {
        String path = normalizePath(objectPath);
        String bucket = getBucket();
        BlobInfo blobInfo = BlobInfo.newBuilder(bucket, path)
                .setContentType(contentType)
                .build();
        Instant expiresAt = clock.instant().plus(ttl);
        Map<String, String> headers = Map.of(
                "Content-Type", contentType,
                "x-goog-content-length-range", "0," + maxBytes
        );

        URL signedUrl = storage.signUrl(
                blobInfo,
                ttl.toMillis(),
                TimeUnit.MILLISECONDS,
                SignUrlOption.httpMethod(HttpMethod.PUT),
                SignUrlOption.withV4Signature(),
                SignUrlOption.withExtHeaders(headers)
        );

        return new SignedUrl(signedUrl.toString(), "PUT", headers, expiresAt);
    }

    @Override
    public SignedUrl signGet(String objectPath, String downloadFileName, Duration ttl) {
        String path = normalizePath(objectPath);
        String bucket = getBucket();
        BlobInfo blobInfo = BlobInfo.newBuilder(bucket, path).build();
        Instant expiresAt = clock.instant().plus(ttl);

        URL signedUrl = storage.signUrl(
                blobInfo,
                ttl.toMillis(),
                TimeUnit.MILLISECONDS,
                SignUrlOption.httpMethod(HttpMethod.GET),
                SignUrlOption.withV4Signature(),
                SignUrlOption.withQueryParams(Map.of(
                        "response-content-disposition", "attachment; filename=\"" + downloadFileName + "\""
                ))
        );

        return new SignedUrl(signedUrl.toString(), "GET", Map.of(), expiresAt);
    }

    @Override
    public Optional<ObjectInfo> stat(String objectPath) {
        String path = normalizePath(objectPath);
        BlobId blobId = BlobId.of(getBucket(), path);
        Blob blob = storage.get(blobId);
        if (blob == null || !blob.exists()) {
            return Optional.empty();
        }

        long size = blob.getSize() != null ? blob.getSize() : 0L;
        String contentType = blob.getContentType() != null ? blob.getContentType() : "application/octet-stream";
        return Optional.of(new ObjectInfo(size, contentType));
    }

    @Override
    public InputStream open(String objectPath) {
        String path = normalizePath(objectPath);
        BlobId blobId = BlobId.of(getBucket(), path);
        Blob blob = storage.get(blobId);
        if (blob == null || !blob.exists()) {
            throw ApiException.notFound(ErrorCodes.BYTES_NOT_FOUND, "Object not found: " + objectPath);
        }

        return Channels.newInputStream(storage.reader(blobId));
    }

    @Override
    public void put(String objectPath, String contentType, InputStream in, long size) {
        String path = normalizePath(objectPath);
        BlobInfo blobInfo = BlobInfo.newBuilder(getBucket(), path)
                .setContentType(contentType)
                .build();
        try {
            storage.createFrom(blobInfo, in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to upload object: " + objectPath, e);
        }
    }

    @Override
    public void delete(String objectPath) {
        String path = normalizePath(objectPath);
        BlobId blobId = BlobId.of(getBucket(), path);
        storage.delete(blobId);
    }

    private String getBucket() {
        String bucket = properties.getBucket();
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("Storage bucket is not configured in dd.storage.bucket");
        }
        return bucket;
    }

    private String normalizePath(String objectPath) {
        if (objectPath == null || objectPath.isBlank()) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Object path must not be empty");
        }
        String normalized = objectPath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank() || normalized.contains("..")) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Invalid object path: " + objectPath);
        }
        return normalized;
    }
}
