package com.deadlinedrop.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Namespace for the submission state machine vocabulary and the flat row shape of the
 * {@code submissions} table. Submissions are persisted with JdbcTemplate (not JPA) so that every
 * transition is an explicit {@code UPDATE ... WHERE id = ? AND status = ?}.
 */
public final class Submission {

    private Submission() {
    }

    /** Lifecycle states; names match the {@code submission_status} Postgres enum. */
    public enum Status {
        COMMITTED,
        UPLOADED,
        VERIFIED,
        REJECTED_HASH_MISMATCH,
        EXPIRED,
        SUPERSEDED
    }

    /** Who caused a transition; names match the {@code event_actor} Postgres enum. */
    public enum Actor {
        COMMIT,
        BROWSER_FINALIZE,
        RECONCILER,
        VERIFIER,
        NEW_COMMIT,
        RECEIPT
    }

    /**
     * One row of {@code submissions}. {@code committedAt} is the authoritative fairness stamp;
     * {@code dbCommittedAt} is the database clock, kept for audit only.
     */
    public record Row(
            UUID id,
            UUID assignmentId,
            UUID studentId,
            UUID idempotencyKey,
            String requestHash,
            String sha256,
            long sizeBytes,
            String contentType,
            String objectPath,
            Status status,
            Timing timing,
            Instant committedAt,
            Instant dbCommittedAt,
            Instant uploadedAt,
            Instant verifiedAt,
            Instant expiredAt,
            Instant supersededAt,
            UUID supersededBy,
            Instant createdAt,
            Instant updatedAt) {
    }
}
