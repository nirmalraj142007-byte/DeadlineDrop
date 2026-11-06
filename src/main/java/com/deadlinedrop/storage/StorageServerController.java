package com.deadlinedrop.storage;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.security.PublicEndpoint;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP controller serving signed storage requests, emulating direct-to-storage upload and download.
 */
@RestController
@ConditionalOnProperty(name = "dd.storage.server.enabled", havingValue = "true")
public class StorageServerController {

    private final StorageProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public StorageServerController(StorageProperties properties, Clock clock, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(properties.getRoot());
    }

    @PutMapping("/storage/**")
    @PublicEndpoint
    public ResponseEntity<Void> putObject(
            HttpServletRequest request,
            @RequestParam(name = "m", required = false) String m,
            @RequestParam(name = "exp", required = false) Long exp,
            @RequestParam(name = "ct", required = false) String ct,
            @RequestParam(name = "max", required = false) Long max,
            @RequestParam(name = "sig", required = false) String sig) throws IOException {

        if (!"PUT".equals(m) || exp == null || ct == null || ct.isBlank() || max == null || sig == null || sig.isBlank()) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Missing or invalid storage signature parameters");
        }

        String objectPath = extractObjectPath(request);
        validatePath(objectPath);

        if (clock.instant().getEpochSecond() > exp) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Storage upload URL has expired");
        }

        String data = "PUT\n" + objectPath + "\n" + exp + "\n" + ct + "\n" + max;
        if (!StorageHmac.verify(data, properties.getHmacSecret(), sig)) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Storage signature mismatch");
        }

        String requestCt = request.getContentType();
        if (requestCt == null) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Missing Content-Type header");
        }
        String baseRequestCt = requestCt.split(";")[0].trim();
        String baseSignedCt = ct.split(";")[0].trim();
        if (!baseRequestCt.equalsIgnoreCase(baseSignedCt)) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Content-Type does not match signed type");
        }

        long contentLength = request.getContentLengthLong();
        if (contentLength > max) {
            throw ApiException.payloadTooLarge(ErrorCodes.FILE_TOO_LARGE,
                    "Content-Length " + contentLength + " exceeds max allowed " + max);
        }

        Path targetFile = properties.getRoot().resolve(objectPath).normalize();
        Path parentDir = targetFile.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }

        Path tempFile = Files.createTempFile(parentDir != null ? parentDir : properties.getRoot(), ".upload-", ".tmp");
        Path tempMeta = Files.createTempFile(parentDir != null ? parentDir : properties.getRoot(), ".meta-", ".tmp");

        try {
            long total = 0;
            try (InputStream in = request.getInputStream();
                 OutputStream out = Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > max) {
                        throw ApiException.payloadTooLarge(ErrorCodes.FILE_TOO_LARGE,
                                "Upload body size exceeded maximum allowed " + max + " bytes");
                    }
                    out.write(buffer, 0, read);
                }
            }

            String metaJson = objectMapper.writeValueAsString(Map.of("contentType", ct));
            Files.writeString(tempMeta, metaJson, StandardCharsets.UTF_8);

            Path metaFile = properties.getRoot().resolve(objectPath + ".meta");
            atomicMove(tempFile, targetFile);
            atomicMove(tempMeta, metaFile);

            return ResponseEntity.ok().build();
        } finally {
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(tempMeta);
        }
    }

    @GetMapping("/storage/**")
    @PublicEndpoint
    public ResponseEntity<InputStreamResource> getObject(
            HttpServletRequest request,
            @RequestParam(name = "m", required = false) String m,
            @RequestParam(name = "exp", required = false) Long exp,
            @RequestParam(name = "fn", required = false) String fn,
            @RequestParam(name = "sig", required = false) String sig) throws IOException {

        if (!"GET".equals(m) || exp == null || fn == null || fn.isBlank() || sig == null || sig.isBlank()) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Missing or invalid storage signature parameters");
        }

        String objectPath = extractObjectPath(request);
        validatePath(objectPath);

        if (clock.instant().getEpochSecond() > exp) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Storage download URL has expired");
        }

        String data = "GET\n" + objectPath + "\n" + exp + "\n" + fn;
        if (!StorageHmac.verify(data, properties.getHmacSecret(), sig)) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Storage signature mismatch");
        }

        Path targetFile = properties.getRoot().resolve(objectPath).normalize();
        if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
            throw ApiException.notFound(ErrorCodes.BYTES_NOT_FOUND, "Object not found: " + objectPath);
        }

        String contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        Path metaFile = properties.getRoot().resolve(objectPath + ".meta");
        if (Files.exists(metaFile)) {
            try {
                JsonNode node = objectMapper.readTree(Files.readAllBytes(metaFile));
                if (node != null && node.has("contentType")) {
                    contentType = node.get("contentType").asText();
                }
            } catch (Exception ignored) {
            }
        }

        long contentLength = Files.size(targetFile);
        InputStream in = Files.newInputStream(targetFile);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fn + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(contentLength)
                .body(new InputStreamResource(in));
    }

    private String extractObjectPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        int storageIndex = uri.indexOf("/storage/");
        if (storageIndex == -1) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Invalid storage URL path");
        }
        String objectPath = uri.substring(storageIndex + "/storage/".length());
        while (objectPath.startsWith("/")) {
            objectPath = objectPath.substring(1);
        }
        if (objectPath.isEmpty()) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Storage object path cannot be empty");
        }
        return objectPath;
    }

    private void validatePath(String objectPath) {
        if (objectPath.contains("..") || objectPath.contains("\\")) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Invalid object path traversal");
        }
        Path rootNormalized = properties.getRoot().normalize();
        Path targetNormalized = properties.getRoot().resolve(objectPath).normalize();
        if (!targetNormalized.startsWith(rootNormalized)) {
            throw ApiException.forbidden(ErrorCodes.SIGNATURE_INVALID, "Invalid object path traversal outside root");
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
