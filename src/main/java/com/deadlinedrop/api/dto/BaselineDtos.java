package com.deadlinedrop.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Data transfer objects for baseline upload responses (arms A and B).
 */
public final class BaselineDtos {

    private BaselineDtos() {
    }

    public record BaselineSubmissionResponse(
            UUID submissionId,
            Instant submittedAt,
            String stampSource,
            String timing
    ) {}

    public record DirectUploadRequest(
            @Positive long sizeBytes,
            @NotBlank @Size(max = 100) String contentType
    ) {}

    public record DirectUploadResponse(
            UUID uploadId,
            String uploadUrl,
            Map<String, String> uploadHeaders,
            Instant urlExpiresAt
    ) {}
}
