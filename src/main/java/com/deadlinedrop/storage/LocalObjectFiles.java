package com.deadlinedrop.storage;

import com.deadlinedrop.storage.StoragePort.ObjectInfo;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/**
 * Reads an object and its {@code .meta} sidecar from the local storage root. Shared by
 * {@link LocalStorageAdapter} and {@link StorageServerController} so both treat a missing or
 * half-written object the same way.
 */
final class LocalObjectFiles {

    private LocalObjectFiles() {
    }

    /**
     * Returns size and content type, or empty when the object or its {@code .meta} file does not
     * exist. Writers move the object into place before its metadata, so an object without
     * metadata is not finished yet and is reported as absent. Any other failure is rethrown.
     */
    static Optional<ObjectInfo> stat(Path object, Path meta, String objectPath, ObjectMapper objectMapper) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(object, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) {
                return Optional.empty();
            }
            byte[] metaBytes = Files.readAllBytes(meta);
            return Optional.of(new ObjectInfo(attrs.size(), contentType(metaBytes, objectPath, objectMapper)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stat object " + objectPath, e);
        }
    }

    private static String contentType(byte[] metaBytes, String objectPath, ObjectMapper objectMapper) {
        JsonNode node;
        try {
            node = objectMapper.readTree(metaBytes);
        } catch (JacksonException e) {
            throw new IllegalStateException("Unreadable metadata for object " + objectPath, e);
        }
        JsonNode contentType = node == null ? null : node.get("contentType");
        if (contentType == null || !contentType.isValueNode() || contentType.asText().isBlank()) {
            throw new IllegalStateException("Metadata for object " + objectPath + " has no contentType");
        }
        return contentType.asText();
    }
}
