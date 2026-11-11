package com.deadlinedrop.api.dto;

import java.time.Instant;
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
}
