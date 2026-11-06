package com.deadlinedrop.repository;

import com.deadlinedrop.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MigrationIT {

    private final JdbcTemplate jdbcTemplate;

    public MigrationIT(@Autowired JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    @DisplayName("1. flyway_schema_history has 5 successful rows")
    void flywaySchemaHistoryHasFiveSuccessfulRows() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true",
                Integer.class
        );
        assertThat(count).isEqualTo(5);
    }

    @Test
    @DisplayName("2. Live submission unique constraint allows re-commitment once marked EXPIRED")
    void liveSubmissionUniqueConstraintAllowsRecommitAfterExpired() {
        UUID teacherId = createTeacher("teacher1@example.com");
        UUID studentId = createStudent("student1@example.com");
        UUID courseId = createCourse("CS101", teacherId);
        UUID assignmentId = createAssignment(courseId, teacherId, 900);

        UUID sub1Id = UUID.randomUUID();
        UUID idemKey1 = UUID.randomUUID();
        String sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Instant now = Instant.now();

        // 1st COMMITTED submission succeeds
        insertSubmission(sub1Id, assignmentId, studentId, idemKey1, sha256, "sub/1.pdf", "COMMITTED", now);

        // 2nd live (COMMITTED) submission for same (assignment_id, student_id) fails
        UUID sub2Id = UUID.randomUUID();
        UUID idemKey2 = UUID.randomUUID();
        assertThrows(DuplicateKeyException.class, () ->
                insertSubmission(sub2Id, assignmentId, studentId, idemKey2, sha256, "sub/2.pdf", "COMMITTED", now)
        );

        // Mark 1st EXPIRED -> status not in live statuses
        jdbcTemplate.update("UPDATE submissions SET status = 'EXPIRED'::submission_status, expired_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), sub1Id);

        // 2nd submission now succeeds
        insertSubmission(sub2Id, assignmentId, studentId, idemKey2, sha256, "sub/2.pdf", "COMMITTED", now);

        String currentStatus = jdbcTemplate.queryForObject(
                "SELECT status::text FROM submissions WHERE id = ?",
                String.class,
                sub2Id
        );
        assertThat(currentStatus).isEqualTo("COMMITTED");
    }

    @Test
    @DisplayName("3. Uppercase sha256 is rejected by check constraint")
    void uppercaseSha256IsRejected() {
        UUID teacherId = createTeacher("teacher3@example.com");
        UUID studentId = createStudent("student3@example.com");
        UUID courseId = createCourse("CS103", teacherId);
        UUID assignmentId = createAssignment(courseId, teacherId, 900);

        UUID subId = UUID.randomUUID();
        UUID idemKey = UUID.randomUUID();
        String upperSha256 = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

        assertThrows(DataIntegrityViolationException.class, () ->
                insertSubmission(subId, assignmentId, studentId, idemKey, upperSha256, "sub/upper.pdf", "COMMITTED", Instant.now())
        );
    }

    @Test
    @DisplayName("4. Grace seconds 3601 is rejected by check constraint")
    void graceSeconds3601IsRejected() {
        UUID teacherId = createTeacher("teacher4@example.com");
        UUID courseId = createCourse("CS104", teacherId);

        UUID assignmentId = UUID.randomUUID();
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update(
                        "INSERT INTO assignments (id, course_id, title, deadline_at, grace_seconds, max_bytes, allowed_types, created_by) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ARRAY['application/pdf'], ?)",
                        assignmentId, courseId, "Lab 4",
                        Timestamp.from(Instant.now().plus(Duration.ofDays(1))),
                        3601, 10000000L, teacherId
                )
        );
    }

    @Test
    @DisplayName("5. Two VERIFY jobs for one submission fails unique constraint")
    void twoVerifyJobsForOneSubmissionFailsUniqueConstraint() {
        UUID teacherId = createTeacher("teacher5@example.com");
        UUID studentId = createStudent("student5@example.com");
        UUID courseId = createCourse("CS105", teacherId);
        UUID assignmentId = createAssignment(courseId, teacherId, 900);

        UUID subId = UUID.randomUUID();
        UUID idemKey = UUID.randomUUID();
        String sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        insertSubmission(subId, assignmentId, studentId, idemKey, sha256, "sub/job.pdf", "COMMITTED", Instant.now());

        // First VERIFY job succeeds
        jdbcTemplate.update(
                "INSERT INTO jobs (type, submission_id, status) VALUES (?::job_type, ?, ?::job_status)",
                "VERIFY", subId, "PENDING"
        );

        // Second VERIFY job for same submission fails unique constraint
        assertThrows(DuplicateKeyException.class, () ->
                jdbcTemplate.update(
                        "INSERT INTO jobs (type, submission_id, status) VALUES (?::job_type, ?, ?::job_status)",
                        "VERIFY", subId, "PENDING"
                )
        );
    }

    @Test
    @DisplayName("6. Duplicate (student_id, idempotency_key) fails unique constraint")
    void duplicateStudentIdAndIdempotencyKeyFails() {
        UUID teacherId = createTeacher("teacher6@example.com");
        UUID studentId = createStudent("student6@example.com");
        UUID courseId1 = createCourse("CS106A", teacherId);
        UUID courseId2 = createCourse("CS106B", teacherId);
        UUID assignmentId1 = createAssignment(courseId1, teacherId, 900);
        UUID assignmentId2 = createAssignment(courseId2, teacherId, 900);

        UUID sharedIdemKey = UUID.randomUUID();
        String sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        insertSubmission(UUID.randomUUID(), assignmentId1, studentId, sharedIdemKey, sha256, "sub/idem1.pdf", "COMMITTED", Instant.now());

        // Inserting with same student_id and idempotency_key on any assignment must violate ux_sub_student_key
        assertThrows(DuplicateKeyException.class, () ->
                insertSubmission(UUID.randomUUID(), assignmentId2, studentId, sharedIdemKey, sha256, "sub/idem2.pdf", "COMMITTED", Instant.now())
        );
    }

    private UUID createTeacher(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, display_name) VALUES (?, ?, ?, ?::user_role, ?)",
                id, email, "$2a$10$hashedpw", "TEACHER", "Teacher Display"
        );
        return id;
    }

    private UUID createStudent(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, display_name) VALUES (?, ?, ?, ?::user_role, ?)",
                id, email, "$2a$10$hashedpw", "STUDENT", "Student Display"
        );
        return id;
    }

    private UUID createCourse(String code, UUID teacherId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO courses (id, code, title, teacher_id) VALUES (?, ?, ?, ?)",
                id, code, "Course " + code, teacherId
        );
        return id;
    }

    private UUID createAssignment(UUID courseId, UUID createdBy, int graceSeconds) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO assignments (id, course_id, title, deadline_at, grace_seconds, max_bytes, allowed_types, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ARRAY['application/pdf'], ?)",
                id, courseId, "Assignment " + id,
                Timestamp.from(Instant.now().plus(Duration.ofDays(2))),
                graceSeconds, 10000000L, createdBy
        );
        return id;
    }

    private void insertSubmission(UUID id, UUID assignmentId, UUID studentId, UUID idempotencyKey,
                                  String sha256, String objectPath, String status, Instant committedAt) {
        jdbcTemplate.update(
                "INSERT INTO submissions (id, assignment_id, student_id, idempotency_key, request_hash, sha256, size_bytes, content_type, object_path, status, timing, committed_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::submission_status, ?::submission_timing, ?)",
                id, assignmentId, studentId, idempotencyKey, sha256, sha256, 2048L, "application/pdf",
                objectPath, status, "ON_TIME", Timestamp.from(committedAt)
        );
    }
}
