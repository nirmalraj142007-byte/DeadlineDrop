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
 * Reads export rows from {@code baseline_submissions} for Arms A (naive) and B (direct-finalize).
 */
@Component
@Profile("!storage & (naive | direct-finalize)")
public class BaselineExportSource implements ExportSource {

    private static final String SQL =
            "SELECT b.student_id, u.email, b.id, b.submitted_at, b.stamp_source, b.timing "
                    + "FROM baseline_submissions b "
                    + "JOIN users u ON b.student_id = u.id "
                    + "WHERE b.assignment_id = ? "
                    + "ORDER BY b.submitted_at ASC, b.id ASC";

    private final JdbcTemplate jdbcTemplate;

    public BaselineExportSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public List<ExportRow> findExportRows(UUID assignmentId) {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        return jdbcTemplate.query(SQL, this::mapRow, assignmentId);
    }

    private ExportRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Instant submittedAt = instant(rs, "submitted_at");
        return new ExportRow(
                rs.getObject("student_id", UUID.class),
                rs.getString("email"),
                rs.getObject("id", UUID.class),
                submittedAt,
                submittedAt,
                "SUBMITTED",
                rs.getString("timing"),
                rs.getString("stamp_source")
        );
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
