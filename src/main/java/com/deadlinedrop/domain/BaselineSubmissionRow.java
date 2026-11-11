package com.deadlinedrop.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model of one row of {@code baseline_submissions} (arms A and B).
 */
public record BaselineSubmissionRow(
        UUID id,
        UUID assignmentId,
        UUID studentId,
        UUID idempotencyKey,
        String objectPath,
        long sizeBytes,
        Instant submittedAt,
        String stampSource,
        Timing timing
) {}
