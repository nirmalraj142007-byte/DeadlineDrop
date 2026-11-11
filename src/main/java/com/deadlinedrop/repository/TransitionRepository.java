package com.deadlinedrop.repository;

import com.deadlinedrop.domain.Submission.Status;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * Status transitions of {@code submissions}. Every transition is one guarded
 * {@code UPDATE ... WHERE id = ? AND status = ?}; the caller checks the rows affected, so two
 * actors racing for the same transition cannot both win.
 *
 * <p>Not final so the race tests can subclass it with an unguarded version and prove the guard
 * is what keeps the invariant.
 */
@Repository
@Profile("!storage")
public class TransitionRepository {

    private static final String TRANSITION_SQL =
            "UPDATE submissions SET status = ?::submission_status, updated_at = ?, "
                    + "uploaded_at = CASE WHEN ?::submission_status = 'UPLOADED' THEN ? ELSE uploaded_at END, "
                    + "verified_at = CASE WHEN ?::submission_status = 'VERIFIED' THEN ? ELSE verified_at END, "
                    + "expired_at = CASE WHEN ?::submission_status = 'EXPIRED' THEN ? ELSE expired_at END "
                    + "WHERE id = ? AND status = ?::submission_status";

    // The grace check uses the app clock passed in, never SQL now(): the app clock is authoritative.
    private static final String EXPIRE_SQL =
            "UPDATE submissions SET status = 'EXPIRED', expired_at = ?, updated_at = ? "
                    + "WHERE id = ? AND status = 'COMMITTED' AND EXISTS (SELECT 1 FROM assignments a "
                    + "WHERE a.id = submissions.assignment_id "
                    + "AND a.deadline_at + make_interval(secs => a.grace_seconds) < ?)";

    private final JdbcTemplate jdbcTemplate;

    public TransitionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /** Moves {@code id} from {@code from} to {@code to}; returns rows affected (0 or 1). */
    public int transition(UUID id, Status from, Status to, Instant now) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(now, "now must not be null");
        OffsetDateTime ts = utc(now);
        return jdbcTemplate.update(TRANSITION_SQL,
                to.name(), ts, to.name(), ts, to.name(), ts, to.name(), ts, id, from.name());
    }

    /** Expires {@code id} only if it is still COMMITTED and its assignment's grace window has ended. */
    public int expireIfGraceOver(UUID id, Instant now) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(now, "now must not be null");
        OffsetDateTime ts = utc(now);
        return jdbcTemplate.update(EXPIRE_SQL, ts, ts, id, ts);
    }

    protected final JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    protected static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
