package com.deadlinedrop.repository;

import com.deadlinedrop.domain.Timing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Direct JDBC repository for experimental baseline submissions (arms A and B).
 */
@Repository
public class BaselineSubmissionRepository {

    private static final String INSERT_SQL =
            "INSERT INTO baseline_submissions (" +
            "  id, assignment_id, student_id, idempotency_key, object_path, size_bytes, submitted_at, stamp_source, timing" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::submission_timing)";

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

    public int countByAssignment(UUID assignmentId) {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Integer count = jdbcTemplate.queryForObject(COUNT_BY_ASSIGNMENT_SQL, Integer.class, assignmentId);
        return count != null ? count : 0;
    }
}
