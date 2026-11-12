package com.deadlinedrop.service;

import com.deadlinedrop.api.dto.DomainDtos.ExportRow;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads export rows from {@code submissions} for Arm C (the hash commitment design).
 */
@Component
@Profile("!storage & !naive & !direct-finalize")
public class SubmissionExportSource implements ExportSource {

    private static final String SQL =
            "SELECT s.student_id, u.email, s.id, s.committed_at, s.uploaded_at, s.status, s.timing "
                    + "FROM submissions s "
                    + "JOIN users u ON s.student_id = u.id "
                    + "WHERE s.assignment_id = ? "
                    + "ORDER BY s.committed_at ASC, s.id ASC";

    private final JdbcTemplate jdbcTemplate;

    public SubmissionExportSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public List<ExportRow> findExportRows(UUID assignmentId) {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        return jdbcTemplate.query(SQL, this::mapRow, assignmentId);
    }

    private ExportRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ExportRow(
                rs.getObject("student_id", UUID.class),
                rs.getString("email"),
                rs.getObject("id", UUID.class),
                instant(rs, "committed_at"),
                instant(rs, "uploaded_at"),
                rs.getString("status"),
                rs.getString("timing"),
                "COMMIT"
        );
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
