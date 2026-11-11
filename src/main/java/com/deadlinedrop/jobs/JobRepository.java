package com.deadlinedrop.jobs;

import com.deadlinedrop.domain.JobRow;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional job queue: jobs are rows in {@code jobs}, inserted in the same transaction as
 * the status transition that needs them, so a job exists if and only if the transition committed.
 * Drainers claim one row at a time with {@code FOR UPDATE SKIP LOCKED}, so two drainers never run
 * the same job and never wait for each other.
 */
@Repository
@Profile("!storage")
public class JobRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private static final String ENQUEUE_VERIFY_SQL =
            "INSERT INTO jobs (type, submission_id, run_after) VALUES ('VERIFY', ?, ?) "
                    + "ON CONFLICT (type, submission_id) DO NOTHING";

    // Served by ix_jobs_ready (run_after WHERE status = 'PENDING').
    private static final String CLAIM_SQL =
            "SELECT id, submission_id, attempts FROM jobs WHERE status = 'PENDING' AND run_after <= ? "
                    + "ORDER BY run_after LIMIT 1 FOR UPDATE SKIP LOCKED";

    private static final String DONE_SQL =
            "UPDATE jobs SET status = 'DONE', updated_at = ? WHERE id = ? AND status = 'PENDING'";

    // updated_at is an audit column only; the retry schedule itself (run_after) comes from the app clock.
    private static final String RETRY_SQL =
            "UPDATE jobs SET attempts = ?, run_after = ?, last_error = ?, updated_at = now() "
                    + "WHERE id = ? AND status = 'PENDING'";

    private static final String FAILED_SQL =
            "UPDATE jobs SET status = 'FAILED', attempts = ?, last_error = ?, updated_at = now() "
                    + "WHERE id = ? AND status = 'PENDING'";

    private final JdbcTemplate jdbcTemplate;

    public JobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /** Enqueues one VERIFY job per submission; a second call for the same submission is a no-op. */
    public void enqueueVerify(UUID submissionId, Instant now) {
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        jdbcTemplate.update(ENQUEUE_VERIFY_SQL, submissionId, utc(now));
    }

    /**
     * Locks and returns the oldest due PENDING job, skipping rows another drainer holds. The lock
     * lasts until the surrounding transaction ends, so this must run inside one; outside a
     * transaction the lock would be released as soon as the SELECT returned.
     */
    public Optional<JobRow> claimNext(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("claimNext must run inside a transaction or the row lock is lost at once");
        }
        return jdbcTemplate.query(CLAIM_SQL,
                        (rs, i) -> new JobRow(rs.getLong("id"), rs.getObject("submission_id", UUID.class), rs.getInt("attempts")),
                        utc(now))
                .stream().findFirst();
    }

    public void markDone(long id, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        expectOneRow(jdbcTemplate.update(DONE_SQL, utc(now), id), id, "DONE");
    }

    /**
     * Schedules another attempt. Runs after the failed attempt's transaction rolled back, so the
     * row is unlocked; returns false if another drainer settled the job in that gap.
     */
    public boolean markRetry(long id, int attempts, Instant runAfter, String error) {
        Objects.requireNonNull(runAfter, "runAfter must not be null");
        return jdbcTemplate.update(RETRY_SQL, attempts, utc(runAfter), truncate(error), id) == 1;
    }

    /** Gives up on the job; returns false if another drainer settled it first (see {@link #markRetry}). */
    public boolean markFailed(long id, int attempts, String error) {
        return jdbcTemplate.update(FAILED_SQL, attempts, truncate(error), id) == 1;
    }

    private static void expectOneRow(int rows, long id, String target) {
        if (rows != 1) {
            throw new IllegalStateException("Job " + id + " was not PENDING when marking it " + target);
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
