package com.deadlinedrop.storage;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Local filesystem implementation of {@link StoragePort} with HMAC-signed URLs,
 * emulating Google Cloud Storage V4 signed URLs in local development and testing.
 */
@Component
@Profile("!cloud")
public class LocalStorageAdapter implements StoragePort {

    private final StorageProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public LocalStorageAdapter(StorageProperties properties, Clock clock, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public SignedUrl signPut(String objectPath, String contentType, long maxBytes, Duration ttl) {
        String path = normalizePath(objectPath);
        Instant expiresAt = clock.instant().plus(ttl);
        long exp = expiresAt.getEpochSecond();

        String data = "PUT\n" + path + "\n" + exp + "\n" + contentType + "\n" + maxBytes;
        String sig = StorageHmac.sign(data, properties.getHmacSecret());

        String encodedCt = URLEncoder.encode(contentType, StandardCharsets.UTF_8);
        String baseUrl = stripTrailingSlash(properties.getPublicBaseUrl());
        String url = baseUrl + "/storage/" + path
                + "?m=PUT&exp=" + exp
                + "&ct=" + encodedCt
                + "&max=" + maxBytes
                + "&sig=" + sig;

        return new SignedUrl(url, "PUT", Map.of("Content-Type", contentType), expiresAt);
    }

    @Override
    public SignedUrl signGet(String objectPath, String downloadFileName, Duration ttl) {
        String path = normalizePath(objectPath);
        Instant expiresAt = clock.instant().plus(ttl);
        long exp = expiresAt.getEpochSecond();

        String data = "GET\n" + path + "\n" + exp + "\n" + downloadFileName;
        String sig = StorageHmac.sign(data, properties.getHmacSecret());

        String encodedFn = URLEncoder.encode(downloadFileName, StandardCharsets.UTF_8);
        String baseUrl = stripTrailingSlash(properties.getPublicBaseUrl());
        String url = baseUrl + "/storage/" + path
                + "?m=GET&exp=" + exp
                + "&fn=" + encodedFn
                + "&sig=" + sig;

        return new SignedUrl(url, "GET", Map.of(), expiresAt);
    }

    @Override
    public Optional<ObjectInfo> stat(String objectPath) {
        String path = normalizePath(objectPath);
        Path targetPath = properties.getRoot().resolve(path).normalize();
        validatePath(targetPath);

        Path metaPath = properties.getRoot().resolve(path + ".meta");
        return LocalObjectFiles.stat(targetPath, metaPath, objectPath, objectMapper);
    }

    @Override
    public InputStream open(String objectPath) {
        String path = normalizePath(objectPath);
        Path targetPath = properties.getRoot().resolve(path).normalize();
        validatePath(targetPath);

        if (!Files.exists(targetPath) || !Files.isRegularFile(targetPath)) {
            throw ApiException.notFound(ErrorCodes.BYTES_NOT_FOUND, "Object not found: " + objectPath);
        }

        try {
            return Files.newInputStream(targetPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open object: " + objectPath, e);
        }
    }

    @Override
    public void put(String objectPath, String contentType, InputStream in, long size) {
        String path = normalizePath(objectPath);
        Path targetPath = properties.getRoot().resolve(path).normalize();
        validatePath(targetPath);

        try {
            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempFile = Files.createTempFile(parent, ".put-", ".tmp");
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                in.transferTo(out);
            }

            Path tempMeta = Files.createTempFile(parent, ".meta-", ".tmp");
            String metaJson = objectMapper.writeValueAsString(Map.of("contentType", contentType));
            Files.writeString(tempMeta, metaJson, StandardCharsets.UTF_8);

            Path metaFile = properties.getRoot().resolve(path + ".meta");
            atomicMove(tempFile, targetPath);
            atomicMove(tempMeta, metaFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to put object: " + objectPath, e);
        }
    }

    @Override
    public void delete(String objectPath) {
        String path = normalizePath(objectPath);
        Path targetPath = properties.getRoot().resolve(path).normalize();
        validatePath(targetPath);

        Path metaPath = properties.getRoot().resolve(path + ".meta");
        try {
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(metaPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete object: " + objectPath, e);
        }
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

    private void validatePath(Path targetPath) {
        if (!targetPath.startsWith(properties.getRoot().normalize())) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Invalid object path traversal");
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
