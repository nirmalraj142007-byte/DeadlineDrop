package com.deadlinedrop.jobs;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * The transactional job queue: jobs are rows in {@code jobs}, inserted in the same transaction as
 * the status transition that needs them, so a job exists if and only if the transition committed.
 */
@Repository
@Profile("!storage")
public class JobRepository {

    private static final String ENQUEUE_VERIFY_SQL =
            "INSERT INTO jobs (type, submission_id, run_after) VALUES ('VERIFY', ?, ?) "
                    + "ON CONFLICT (type, submission_id) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    public JobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /** Enqueues one VERIFY job per submission; a second call for the same submission is a no-op. */
    public void enqueueVerify(UUID submissionId, Instant now) {
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        jdbcTemplate.update(ENQUEUE_VERIFY_SQL, submissionId, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }
}
