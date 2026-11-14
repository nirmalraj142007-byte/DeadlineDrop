package com.deadlinedrop.repository;

import com.deadlinedrop.domain.Submission.Actor;
import com.deadlinedrop.domain.Submission.Row;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.domain.Timing;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JdbcTemplate access to {@code submissions} and {@code submission_events}. All SQL uses bind
 * parameters; enum columns are bound as text and cast in SQL.
 */
@Repository
@Profile("!storage")
public class SubmissionRepository {

    /** A live submission that was just superseded, with the status it held beforehand. */
    public record Superseded(UUID id, Status previousStatus) {
    }

    public record LiveSubmissionSummary(
            UUID id,
            UUID assignmentId,
            Status status,
            Timing timing,
            Instant committedAt
    ) {
    }

    public record SubmissionEventRecord(
            String from,
            String to,
            String actor,
            Instant at
    ) {
    }

    private static final String FIND_LIVE_BY_STUDENT_SQL =
            "SELECT id, assignment_id, status, timing, committed_at "
                    + "FROM submissions "
                    + "WHERE student_id = ? AND status NOT IN ('EXPIRED','SUPERSEDED')";

    private static final String SELECT_COLUMNS =
            "SELECT id, assignment_id, student_id, idempotency_key, request_hash, sha256, size_bytes, content_type, "
                    + "object_path, status, timing, committed_at, db_committed_at, uploaded_at, verified_at, "
                    + "expired_at, superseded_at, superseded_by, created_at, updated_at FROM submissions ";

    private static final String SUPERSEDE_SQL =
            "WITH old AS (SELECT id, status FROM submissions "
                    + "WHERE assignment_id = ? AND student_id = ? AND status NOT IN ('EXPIRED','SUPERSEDED') FOR UPDATE) "
                    + "UPDATE submissions s SET status = 'SUPERSEDED', superseded_at = ?, updated_at = ? "
                    + "FROM old WHERE s.id = old.id RETURNING s.id, old.status";

    private static final String INSERT_SQL =
            "INSERT INTO submissions (id, assignment_id, student_id, idempotency_key, request_hash, sha256, "
                    + "size_bytes, content_type, object_path, status, timing, committed_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::submission_status, ?::submission_timing, ?)";

    private static final String LINK_SQL = "UPDATE submissions SET superseded_by = ? WHERE id = ?";

    private static final String EVENT_SQL =
            "INSERT INTO submission_events (submission_id, from_status, to_status, actor, at) "
                    + "VALUES (?, ?::submission_status, ?::submission_status, ?::event_actor, ?)";

    private static final String RECONCILE_CANDIDATES_SQL =
            "SELECT id FROM submissions WHERE status = 'COMMITTED' AND committed_at < ? "
                    + "ORDER BY committed_at LIMIT ?";

    private static final RowMapper<Row> ROW_MAPPER = SubmissionRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public SubmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    public Optional<Row> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return jdbcTemplate.query(SELECT_COLUMNS + "WHERE id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    public Optional<Row> findByStudentAndKey(UUID studentId, UUID key) {
        Objects.requireNonNull(studentId, "studentId must not be null");
        Objects.requireNonNull(key, "key must not be null");
        return jdbcTemplate.query(SELECT_COLUMNS + "WHERE student_id = ? AND idempotency_key = ?", ROW_MAPPER, studentId, key)
                .stream().findFirst();
    }

    /** Supersedes the live submission (if any) for this student and assignment, locking it first. */
    public List<Superseded> supersedeLive(UUID assignmentId, UUID studentId, Instant now) {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(studentId, "studentId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        OffsetDateTime ts = utc(now);
        return jdbcTemplate.query(
                SUPERSEDE_SQL,
                (rs, i) -> new Superseded(rs.getObject(1, UUID.class), Status.valueOf(rs.getString(2))),
                assignmentId, studentId, ts, ts);
    }

    /** Inserts a COMMITTED row; {@code db_committed_at}, {@code created_at}, {@code updated_at} take DB defaults. */
    public void insertCommitted(Row row) {
        Objects.requireNonNull(row, "row must not be null");
        jdbcTemplate.update(
                INSERT_SQL,
                row.id(), row.assignmentId(), row.studentId(), row.idempotencyKey(), row.requestHash(), row.sha256(),
                row.sizeBytes(), row.contentType(), row.objectPath(), row.status().name(), row.timing().name(),
                utc(row.committedAt()));
    }

    public void linkSupersededBy(List<UUID> oldIds, UUID newId) {
        Objects.requireNonNull(oldIds, "oldIds must not be null");
        Objects.requireNonNull(newId, "newId must not be null");
        for (UUID oldId : oldIds) {
            jdbcTemplate.update(LINK_SQL, newId, oldId);
        }
    }

    /**
     * COMMITTED rows stamped before {@code before}, oldest first: the reconciler's candidates.
     * Served by {@code ix_sub_reconcile (status, committed_at)}.
     */
    public List<UUID> findCommittedBefore(Instant before, int limit) {
        Objects.requireNonNull(before, "before must not be null");
        return jdbcTemplate.queryForList(RECONCILE_CANDIDATES_SQL, UUID.class, utc(before), limit);
    }

    public List<LiveSubmissionSummary> findLiveByStudent(UUID studentId) {
        Objects.requireNonNull(studentId, "studentId must not be null");
        return jdbcTemplate.query(
                FIND_LIVE_BY_STUDENT_SQL,
                (rs, i) -> new LiveSubmissionSummary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("assignment_id", UUID.class),
                        Status.valueOf(rs.getString("status")),
                        Timing.valueOf(rs.getString("timing")),
                        instant(rs, "committed_at")),
                studentId);
    }

    public void insertEvent(UUID id, Status from, Status to, Actor actor, Instant at) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(at, "at must not be null");
        jdbcTemplate.update(EVENT_SQL, id, from == null ? null : from.name(), to.name(), actor.name(), utc(at));
    }

    public List<SubmissionEventRecord> findEvents(UUID submissionId) {
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        return jdbcTemplate.query(
                "SELECT from_status, to_status, actor, at FROM submission_events WHERE submission_id = ? ORDER BY at ASC, id ASC",
                (rs, i) -> new SubmissionEventRecord(
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("actor"),
                        instant(rs, "at")),
                submissionId);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("assignment_id", UUID.class),
                rs.getObject("student_id", UUID.class),
                rs.getObject("idempotency_key", UUID.class),
                rs.getString("request_hash"),
                rs.getString("sha256"),
                rs.getLong("size_bytes"),
                rs.getString("content_type"),
                rs.getString("object_path"),
                Status.valueOf(rs.getString("status")),
                Timing.valueOf(rs.getString("timing")),
                instant(rs, "committed_at"),
                instant(rs, "db_committed_at"),
                instant(rs, "uploaded_at"),
                instant(rs, "verified_at"),
                instant(rs, "expired_at"),
                instant(rs, "superseded_at"),
                rs.getObject("superseded_by", UUID.class),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
