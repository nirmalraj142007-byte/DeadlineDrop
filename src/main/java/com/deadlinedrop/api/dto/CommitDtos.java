package com.deadlinedrop.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Request and response bodies of the commit step.
 */
public final class CommitDtos {

    private CommitDtos() {
    }

    public record CommitRequest(
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sha256,
            @Positive long sizeBytes,
            @NotBlank @Size(max = 100) String contentType) {
    }

    /**
     * {@code uploadUrl}, {@code uploadHeaders} and {@code urlExpiresAt} are null when signing failed.
     * {@code receipt} is null unless the database was unreachable: then {@code status} is
     * RECEIPT_ISSUED, {@code timing} is PENDING, and the browser sends the receipt back at finalize.
     */
    public record CommitResponse(
            UUID submissionId,
            Instant committedAt,
            String timing,
            String status,
            String uploadUrl,
            Map<String, String> uploadHeaders,
            Instant urlExpiresAt,
            String receipt) {
    }

    /** Optional finalize body; {@code receipt} is present only for a commit that was answered with one. */
    public record FinalizeRequest(String receipt) {
    }

    /** Finalize result; {@code uploadedAt} is null while the submission is still COMMITTED. */
    public record SubmissionStatusResponse(
            UUID submissionId,
            String status,
            String timing,
            Instant uploadedAt) {
    }

    /** Asks for a fresh upload URL for the file that was committed; the hash must match the commit. */
    public record ReissueRequest(@NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sha256) {
    }

    public record UploadUrlResponse(String uploadUrl, Map<String, String> uploadHeaders, Instant urlExpiresAt) {
    }
}
