package com.deadlinedrop.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReadDtos {

    private ReadDtos() {
    }

    public record DashboardRow(
            UUID studentId,
            String email,
            String displayName,
            UUID submissionId,
            String status,
            String timing,
            Instant committedAt,
            Instant uploadedAt,
            Instant verifiedAt
    ) {
    }

    public record DashboardDto(
            UUID assignmentId,
            String title,
            Instant deadlineAt,
            Instant graceEndsAt,
            Map<String, Long> countsByStatus,
            Map<String, Long> countsByTiming,
            long failedJobs,
            List<DashboardRow> rows
    ) {
    }

    public record EventDto(
            String from,
            String to,
            String actor,
            Instant at
    ) {
    }

    public record ReceiptDto(
            UUID submissionId,
            UUID assignmentId,
            String assignmentTitle,
            Instant committedAt,
            String sha256,
            long sizeBytes,
            String contentType,
            String timing,
            String status,
            Instant uploadedAt,
            Instant verifiedAt,
            Instant graceEndsAt,
            List<EventDto> events
    ) {
    }

    public record DownloadUrlResponse(
            String url,
            Instant expiresAt
    ) {
    }
}
