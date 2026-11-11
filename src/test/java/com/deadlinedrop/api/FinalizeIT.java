package com.deadlinedrop.api;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.dto.CommitDtos.CommitRequest;
import com.deadlinedrop.api.dto.CommitDtos.CommitResponse;
import com.deadlinedrop.api.dto.CommitDtos.SubmissionStatusResponse;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Enrollment;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.security.JwtService;
import com.deadlinedrop.service.ReconcilerService;
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FinalizeIT.TestClockConfig.class})
class FinalizeIT {

    private static final Path TEMP_ROOT;
    private static final String SHA = "c".repeat(64);
    private static final int SIZE = 1024;

    static {
        try {
            TEMP_ROOT = Files.createTempDirectory("dd-finalize-it-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.of(Instant.parse("2026-11-20T12:00:00Z"));
        }
    }

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry registry) {
        registry.add("dd.storage.server.enabled", () -> "true");
        registry.add("dd.storage.root", () -> TEMP_ROOT.toAbsolutePath().toString());
    }

    private final TestRestTemplate rest;
    private final MutableClock clock;
    private final UserRepository users;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final AssignmentRepository assignments;
    private final StoragePort storage;
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ReconcilerService reconciler;
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    private User teacher;
    private User student;
    private User otherStudent;
    private Assignment assignment;
    private Instant deadline;

    @Autowired
    FinalizeIT(TestRestTemplate rest, MutableClock clock, UserRepository users, CourseRepository courses,
            EnrollmentRepository enrollments, AssignmentRepository assignments, StoragePort storage,
            JwtService jwtService, JdbcTemplate jdbc, ObjectMapper objectMapper, ReconcilerService reconciler) {
        this.rest = rest;
        this.clock = clock;
        this.users = users;
        this.courses = courses;
        this.enrollments = enrollments;
        this.assignments = assignments;
        this.storage = storage;
        this.jwtService = jwtService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.reconciler = reconciler;
    }

    @BeforeEach
    void setUp() {
        clean();
        deadline = Instant.parse("2026-11-20T23:59:59Z");
        clock.set(Instant.parse("2026-11-20T12:00:00Z"));
        teacher = users.save(User.create("fin-teacher@dd.local", "unused-hash", Role.TEACHER, "Finalize Teacher"));
        student = users.save(User.create("fin-student1@dd.local", "unused-hash", Role.STUDENT, "Finalize One"));
        otherStudent = users.save(User.create("fin-student2@dd.local", "unused-hash", Role.STUDENT, "Finalize Two"));
        Course course = courses.save(Course.create("FIN101", "Finalize course", teacher.getId()));
        enrollments.save(Enrollment.enroll(course.getId(), student.getId()));
        enrollments.save(Enrollment.enroll(course.getId(), otherStudent.getId()));
        assignment = assignments.save(Assignment.create(course.getId(), "Lab 1", deadline, 900, 1_048_576L,
                List.of("application/pdf", "application/zip"), teacher.getId()));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM submission_events");
        jdbc.update("UPDATE submissions SET superseded_by = NULL");
        jdbc.update("DELETE FROM submissions");
        enrollments.deleteAll();
        assignments.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    private HttpHeaders auth(User as) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (as != null) {
            headers.setBearerAuth(jwtService.issue(as).accessToken());
        }
        return headers;
    }

    private CommitResponse commit(User as) {
        HttpHeaders headers = auth(as);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<String> r = rest.postForEntity("/api/assignments/" + assignment.getId() + "/commits",
                new HttpEntity<>(new CommitRequest(SHA, SIZE, "application/pdf"), headers), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readValue(r.getBody(), CommitResponse.class);
    }

    private int put(CommitResponse c, byte[] bytes) throws Exception {
        URI signed = URI.create(c.uploadUrl());
        URI local = new URI("http", null, "localhost", port, signed.getPath(), signed.getQuery(), null);
        HttpRequest.Builder put = HttpRequest.newBuilder(local).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        c.uploadHeaders().forEach(put::header);
        return http.send(put.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private ResponseEntity<String> finalizeAs(User as, UUID id) {
        return rest.postForEntity("/api/submissions/" + id + "/finalize", new HttpEntity<>(null, auth(as)), String.class);
    }

    private String code(ResponseEntity<String> r) {
        return objectMapper.readTree(r.getBody()).get("code").asText();
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status::text FROM submissions WHERE id = ?", String.class, id);
    }

    private String objectPath(UUID id) {
        return jdbc.queryForObject("SELECT object_path FROM submissions WHERE id = ?", String.class, id);
    }

    private int count(String sql, UUID id) {
        Integer n = jdbc.queryForObject(sql, Integer.class, id);
        return n == null ? 0 : n;
    }

    @Test
    void finalizeAfterPutReturnsUploadedOnceAndRepeatsIdempotently() throws Exception {
        CommitResponse c = commit(student);
        assertThat(put(c, new byte[SIZE])).isEqualTo(200);
        Instant finalizeAt = Instant.parse("2026-11-20T12:00:07Z");
        clock.set(finalizeAt);

        ResponseEntity<String> r = finalizeAs(student, c.submissionId());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        SubmissionStatusResponse body = objectMapper.readValue(r.getBody(), SubmissionStatusResponse.class);
        assertThat(body.submissionId()).isEqualTo(c.submissionId());
        assertThat(body.status()).isEqualTo("UPLOADED");
        assertThat(body.timing()).isEqualTo("ON_TIME");
        assertThat(body.uploadedAt()).isEqualTo(finalizeAt);
        Instant committedAt = jdbc.queryForObject("SELECT committed_at FROM submissions WHERE id = ?",
                java.sql.Timestamp.class, c.submissionId()).toInstant();
        assertThat(committedAt).as("finalize never moves the stamp").isEqualTo(c.committedAt());

        clock.advance(Duration.ofSeconds(5));
        ResponseEntity<String> again = finalizeAs(student, c.submissionId());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readValue(again.getBody(), SubmissionStatusResponse.class).uploadedAt())
                .isEqualTo(finalizeAt);

        assertThat(count("SELECT count(*) FROM submission_events WHERE submission_id = ? AND to_status = 'UPLOADED' "
                + "AND actor = 'BROWSER_FINALIZE'", c.submissionId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM jobs WHERE submission_id = ? AND type = 'VERIFY' AND status = 'PENDING'",
                c.submissionId())).isEqualTo(1);
    }

    @Test
    void finalizeBeforePutReturns404BytesNotFoundAndStaysCommitted() {
        CommitResponse c = commit(student);

        ResponseEntity<String> r = finalizeAs(student, c.submissionId());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(r)).isEqualTo(ErrorCodes.BYTES_NOT_FOUND);
        assertThat(status(c.submissionId())).isEqualTo("COMMITTED");
    }

    @Test
    void wrongSizeReturns422AndDeletesTheObject() throws Exception {
        CommitResponse c = commit(student);
        assertThat(put(c, new byte[SIZE / 2])).isEqualTo(200);

        ResponseEntity<String> r = finalizeAs(student, c.submissionId());

        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(code(r)).isEqualTo(ErrorCodes.SIZE_MISMATCH);
        assertThat(storage.stat(objectPath(c.submissionId()))).as("object deleted").isEmpty();
        assertThat(status(c.submissionId())).isEqualTo("COMMITTED");
    }

    @Test
    void wrongContentTypeReturns422AndDeletesTheObject() {
        CommitResponse c = commit(student);
        // The signed URL refuses a different Content-Type, so write the mismatching object through
        // the real storage adapter, as a storage backend that ignored the signed type would.
        String path = objectPath(c.submissionId());
        storage.put(path, "application/zip", new ByteArrayInputStream(new byte[SIZE]), SIZE);

        ResponseEntity<String> r = finalizeAs(student, c.submissionId());

        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(code(r)).isEqualTo(ErrorCodes.CONTENT_TYPE_MISMATCH);
        assertThat(storage.stat(path)).as("object deleted").isEmpty();
    }

    @Test
    void anotherStudentGets403() throws Exception {
        CommitResponse c = commit(student);
        assertThat(put(c, new byte[SIZE])).isEqualTo(200);

        ResponseEntity<String> r = finalizeAs(otherStudent, c.submissionId());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(r)).isEqualTo(ErrorCodes.FORBIDDEN);
        assertThat(status(c.submissionId())).isEqualTo("COMMITTED");
    }

    @Test
    void unknownSubmissionGets404AndTeacherGets403AndAnonymousGets401() {
        ResponseEntity<String> unknown = finalizeAs(student, UUID.randomUUID());
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(unknown)).isEqualTo(ErrorCodes.SUBMISSION_NOT_FOUND);

        CommitResponse c = commit(student);
        assertThat(finalizeAs(teacher, c.submissionId()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(finalizeAs(null, c.submissionId()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void reconcilerUploadsWhenTheBrowserNeverFinalizes() throws Exception {
        CommitResponse c = commit(student);
        assertThat(put(c, new byte[SIZE])).isEqualTo(200);

        assertThat(reconciler.settle(c.submissionId())).isEqualTo(Status.UPLOADED);
        assertThat(count("SELECT count(*) FROM submission_events WHERE submission_id = ? AND actor = 'RECONCILER'",
                c.submissionId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM jobs WHERE submission_id = ?", c.submissionId())).isEqualTo(1);
    }

    @Test
    void reconcilerExpiresMissingBytesOnlyAfterGrace() {
        CommitResponse c = commit(student);

        clock.set(deadline.plusSeconds(900));
        assertThat(reconciler.settle(c.submissionId())).as("grace end itself is still inside grace")
                .isEqualTo(Status.COMMITTED);

        clock.set(deadline.plusSeconds(901));
        assertThat(reconciler.settle(c.submissionId())).isEqualTo(Status.EXPIRED);
        assertThat(count("SELECT count(*) FROM submission_events WHERE submission_id = ? AND to_status = 'EXPIRED'",
                c.submissionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT expired_at FROM submissions WHERE id = ?", java.sql.Timestamp.class,
                c.submissionId()).toInstant()).isEqualTo(deadline.plusSeconds(901));
    }
}
