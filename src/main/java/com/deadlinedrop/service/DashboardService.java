package com.deadlinedrop.service;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.api.dto.ReadDtos.DashboardDto;
import com.deadlinedrop.api.dto.ReadDtos.DashboardRow;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.jobs.JobRepository;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Profile("!storage")
public class DashboardService {

    public record DashboardResult(DashboardDto dto, String etag) {
    }

    private static final String DASHBOARD_SQL =
            "SELECT "
                    + "  e.student_id, "
                    + "  u.email, "
                    + "  u.display_name, "
                    + "  sub.id AS submission_id, "
                    + "  COALESCE(sub.status, 'NOT_SUBMITTED') AS status, "
                    + "  sub.timing, "
                    + "  sub.committed_at, "
                    + "  sub.uploaded_at, "
                    + "  sub.verified_at, "
                    + "  COALESCE(sub.updated_at, u.updated_at) AS row_updated_at "
                    + "FROM enrollments e "
                    + "JOIN users u ON e.student_id = u.id "
                    + "JOIN assignments a ON a.id = ? "
                    + "LEFT JOIN LATERAL ( "
                    + "  SELECT s.id, s.status::text AS status, s.timing::text AS timing, "
                    + "         s.committed_at, s.uploaded_at, s.verified_at, s.updated_at "
                    + "  FROM submissions s "
                    + "  WHERE s.assignment_id = a.id "
                    + "    AND s.student_id = e.student_id "
                    + "    AND ( "
                    + "      s.status NOT IN ('EXPIRED', 'SUPERSEDED') "
                    + "      OR s.status IN ('EXPIRED', 'REJECTED_HASH_MISMATCH') "
                    + "    ) "
                    + "  ORDER BY "
                    + "    CASE WHEN s.status NOT IN ('EXPIRED', 'SUPERSEDED') THEN 0 ELSE 1 END, "
                    + "    s.committed_at DESC, "
                    + "    s.id DESC "
                    + "  LIMIT 1 "
                    + ") sub ON true "
                    + "WHERE e.course_id = a.course_id "
                    + "ORDER BY u.email ASC";

    private final AssignmentRepository assignmentRepository;
    private final CourseRepository courseRepository;
    private final JobRepository jobRepository;
    private final JdbcTemplate jdbcTemplate;

    public DashboardService(
            AssignmentRepository assignmentRepository,
            CourseRepository courseRepository,
            JobRepository jobRepository,
            JdbcTemplate jdbcTemplate) {
        this.assignmentRepository = Objects.requireNonNull(assignmentRepository, "assignmentRepository must not be null");
        this.courseRepository = Objects.requireNonNull(courseRepository, "courseRepository must not be null");
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Transactional(readOnly = true)
    public DashboardResult getDashboard(UUID assignmentId, UUID callerTeacherId) {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(callerTeacherId, "callerTeacherId must not be null");

        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.ASSIGNMENT_NOT_FOUND,
                        "Assignment not found: " + assignmentId));

        Course course = courseRepository.findById(assignment.getCourseId())
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.NOT_FOUND,
                        "Course not found: " + assignment.getCourseId()));

        if (!course.getTeacherId().equals(callerTeacherId)) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN, "Not course owner for assignment " + assignmentId);
        }

        List<DashboardRow> rows = new ArrayList<>();
        List<Instant> rowUpdatedAts = new ArrayList<>();

        jdbcTemplate.query(DASHBOARD_SQL, rs -> {
            rows.add(mapRow(rs));
            Instant rowUpdatedAt = instant(rs, "row_updated_at");
            if (rowUpdatedAt != null) {
                rowUpdatedAts.add(rowUpdatedAt);
            }
        }, assignmentId);

        Map<String, Long> countsByStatus = rows.stream()
                .collect(Collectors.groupingBy(DashboardRow::status, TreeMap::new, Collectors.counting()));

        Map<String, Long> countsByTiming = rows.stream()
                .filter(r -> r.timing() != null)
                .collect(Collectors.groupingBy(DashboardRow::timing, TreeMap::new, Collectors.counting()));

        long failedJobs = jobRepository.countFailedForAssignment(assignmentId);

        Instant maxUpdatedAt = rowUpdatedAts.stream()
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(assignment.getUpdatedAt());
        if (assignment.getUpdatedAt() != null && (maxUpdatedAt == null || assignment.getUpdatedAt().isAfter(maxUpdatedAt))) {
            maxUpdatedAt = assignment.getUpdatedAt();
        }

        String etag = computeEtag(countsByStatus, countsByTiming, failedJobs, rows.size(), maxUpdatedAt);

        DashboardDto dto = new DashboardDto(
                assignment.getId(),
                assignment.getTitle(),
                assignment.getDeadlineAt(),
                assignment.graceEndsAt(),
                countsByStatus,
                countsByTiming,
                failedJobs,
                rows
        );

        return new DashboardResult(dto, etag);
    }

    private static String computeEtag(
            Map<String, Long> countsByStatus,
            Map<String, Long> countsByTiming,
            long failedJobs,
            int rowCount,
            Instant maxUpdatedAt) {
        String raw = String.format("%s|%s|%d|%d|%s",
                countsByStatus,
                countsByTiming,
                failedJobs,
                rowCount,
                maxUpdatedAt != null ? maxUpdatedAt.toString() : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private static DashboardRow mapRow(ResultSet rs) throws SQLException {
        return new DashboardRow(
                rs.getObject("student_id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getObject("submission_id", UUID.class),
                rs.getString("status"),
                rs.getString("timing"),
                instant(rs, "committed_at"),
                instant(rs, "uploaded_at"),
                instant(rs, "verified_at")
        );
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
