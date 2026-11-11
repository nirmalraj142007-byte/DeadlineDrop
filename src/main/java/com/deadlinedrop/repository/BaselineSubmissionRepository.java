package com.deadlinedrop.repository;

import com.deadlinedrop.domain.BaselineSubmissionRow;
import com.deadlinedrop.domain.Timing;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Direct JDBC repository for experimental baseline submissions (arms A and B).
 */
@Repository
@Profile("!storage")
public class BaselineSubmissionRepository {

    private static final String INSERT_SQL =
            "INSERT INTO baseline_submissions (" +
            "  id, assignment_id, student_id, idempotency_key, object_path, size_bytes, submitted_at, stamp_source, timing" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::submission_timing)";

    private static final String INSERT_IF_ABSENT_SQL = INSERT_SQL +
            " ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING";

    private static final String FIND_BY_KEY_SQL =
            "SELECT id, assignment_id, student_id, idempotency_key, object_path, size_bytes, submitted_at, " +
            "stamp_source, timing::text AS timing_text FROM baseline_submissions WHERE idempotency_key = ?";

    private static final String COUNT_BY_ASSIGNMENT_SQL =
            "SELECT count(*) FROM baseline_submissions WHERE assignment_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public BaselineSubmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    public void insert(
            UUID id,
            UUID assignmentId,
            UUID studentId,
            UUID idempotencyKeyOrNull,
            String objectPath,
            long size,
            Instant submittedAt,
            String stampSource,
            Timing timing) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(studentId, "studentId must not be null");
        Objects.requireNonNull(objectPath, "objectPath must not be null");
        Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        Objects.requireNonNull(stampSource, "stampSource must not be null");
        Objects.requireNonNull(timing, "timing must not be null");

        jdbcTemplate.update(
                INSERT_SQL,
                id,
                assignmentId,
                studentId,
                idempotencyKeyOrNull,
                objectPath,
                size,
                Timestamp.from(submittedAt),
                stampSource,
                timing.name()
        );
    }

    /**
     * Inserts the row unless one with the same idempotency key already exists.
     *
     * @return true if a row was inserted, false if the key was already present (a replay)
     */
    public boolean insertIfAbsent(
            UUID id,
            UUID assignmentId,
            UUID studentId,
            UUID idempotencyKey,
            String objectPath,
            long size,
            Instant submittedAt,
            String stampSource,
            Timing timing) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(studentId, "studentId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(objectPath, "objectPath must not be null");
        Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        Objects.requireNonNull(stampSource, "stampSource must not be null");
        Objects.requireNonNull(timing, "timing must not be null");

        int rows = jdbcTemplate.update(
                INSERT_IF_ABSENT_SQL,
                id,
                assignmentId,
                studentId,
                idempotencyKey,
                objectPath,
                size,
                Timestamp.from(submittedAt),
                stampSource,
                timing.name()
        );
        return rows == 1;
    }

    public Optional<BaselineSubmissionRow> findByIdempotencyKey(UUID idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        List<BaselineSubmissionRow> rows = jdbcTemplate.query(
                FIND_BY_KEY_SQL,
                (rs, i) -> new BaselineSubmissionRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("assignment_id", UUID.class),
                        rs.getObject("student_id", UUID.class),
                        rs.getObject("idempotency_key", UUID.class),
                        rs.getString("object_path"),
                        rs.getLong("size_bytes"),
                        rs.getTimestamp("submitted_at").toInstant(),
                        rs.getString("stamp_source"),
                        Timing.valueOf(rs.getString("timing_text"))),
                idempotencyKey
        );
        return rows.stream().findFirst();
    }

    public int countByAssignment(UUID assignmentId) {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Integer count = jdbcTemplate.queryForObject(COUNT_BY_ASSIGNMENT_SQL, Integer.class, assignmentId);
        return count != null ? count : 0;
    }
}
