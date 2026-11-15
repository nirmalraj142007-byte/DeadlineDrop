package com.deadlinedrop.api;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.dto.CommitDtos.CommitRequest;
import com.deadlinedrop.api.dto.CommitDtos.CommitResponse;
import com.deadlinedrop.api.dto.CommitDtos.ReissueRequest;
import com.deadlinedrop.api.dto.CommitDtos.UploadUrlResponse;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Enrollment;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.security.JwtService;
import com.deadlinedrop.support.MutableClock;
import com.deadlinedrop.support.TestTokens;
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

/** POST /api/submissions/{id}/upload-url: the rules for getting a fresh URL after the first one is lost. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, UrlReissueIT.TestClockConfig.class})
class UrlReissueIT {

    private static final Path TEMP_ROOT;
    private static final String SHA = "d".repeat(64);
    private static final String OTHER_SHA = "e".repeat(64);
    private static final int SIZE = 1024;

    static {
        try {
            TEMP_ROOT = Files.createTempDirectory("dd-reissue-it-");
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
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    private User teacher;
    private User student;
    private User otherStudent;
    private Assignment assignment;
    private Instant deadline;

    @Autowired
    UrlReissueIT(TestRestTemplate rest, MutableClock clock, UserRepository users, CourseRepository courses,
            EnrollmentRepository enrollments, AssignmentRepository assignments, JwtService jwtService,
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.rest = rest;
        this.clock = clock;
        this.users = users;
        this.courses = courses;
        this.enrollments = enrollments;
        this.assignments = assignments;
        this.jwtService = jwtService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void setUp() {
        clean();
        deadline = Instant.parse("2026-11-20T23:59:59Z");
        clock.set(Instant.parse("2026-11-20T12:00:00Z"));
        teacher = users.save(User.create("reissue-teacher@dd.local", "unused-hash", Role.TEACHER, "Reissue Teacher"));
        student = users.save(User.create("reissue-student1@dd.local", "unused-hash", Role.STUDENT, "Reissue One"));
        otherStudent = users.save(User.create("reissue-student2@dd.local", "unused-hash", Role.STUDENT, "Reissue Two"));
        Course course = courses.save(Course.create("REI101", "Reissue course", teacher.getId()));
        enrollments.save(Enrollment.enroll(course.getId(), student.getId()));
        enrollments.save(Enrollment.enroll(course.getId(), otherStudent.getId()));
        assignment = assignments.save(Assignment.create(course.getId(), "Lab 1", deadline, 900, 1_048_576L,
                List.of("application/pdf"), teacher.getId()));
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
            headers.setBearerAuth(TestTokens.accessToken(jwtService, as));
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

    private ResponseEntity<String> reissue(User as, UUID id, String sha) {
        return rest.postForEntity("/api/submissions/" + id + "/upload-url",
                new HttpEntity<>(new ReissueRequest(sha), auth(as)), String.class);
    }

    private int put(String uploadUrl, java.util.Map<String, String> uploadHeaders, byte[] bytes) throws Exception {
        URI signed = URI.create(uploadUrl);
        URI local = new URI("http", null, "localhost", port, signed.getPath(), signed.getQuery(), null);
        HttpRequest.Builder put = HttpRequest.newBuilder(local).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        uploadHeaders.forEach(put::header);
        return http.send(put.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private String code(ResponseEntity<String> r) {
        return objectMapper.readTree(r.getBody()).get("code").asText();
    }

    private String detail(ResponseEntity<String> r) {
        return objectMapper.readTree(r.getBody()).get("detail").asText();
    }

    @Test
    void sameHashGetsAFreshUrlThatCarriesTheCommittedBytesAndFinalizes() throws Exception {
        CommitResponse c = commit(student);
        clock.advance(Duration.ofMinutes(20));

        ResponseEntity<String> r = reissue(student, c.submissionId(), SHA);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        UploadUrlResponse body = objectMapper.readValue(r.getBody(), UploadUrlResponse.class);
        assertThat(body.uploadUrl()).isNotBlank();
        assertThat(body.urlExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(put(body.uploadUrl(), body.uploadHeaders(), new byte[SIZE])).isEqualTo(200);

        ResponseEntity<String> fin = rest.postForEntity("/api/submissions/" + c.submissionId() + "/finalize",
                new HttpEntity<>(null, auth(student)), String.class);
        assertThat(fin.getStatusCode()).isEqualTo(HttpStatus.OK);
        Instant committedAt = jdbc.queryForObject("SELECT committed_at FROM submissions WHERE id = ?",
                java.sql.Timestamp.class, c.submissionId()).toInstant();
        assertThat(committedAt).as("re-issuing a URL never moves the stamp").isEqualTo(c.committedAt());
    }

    @Test
    void differentHashBeforeTheDeadlineIsToldToCommitAgain() {
        CommitResponse c = commit(student);

        ResponseEntity<String> r = reissue(student, c.submissionId(), OTHER_SHA);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(r)).isEqualTo(ErrorCodes.WRONG_STATE);
        assertThat(detail(r)).isEqualTo("commit again to change the file");
    }

    @Test
    void differentHashAfterTheDeadlineButInsideGraceIsForbidden() {
        CommitResponse c = commit(student);
        clock.set(deadline.plusSeconds(10));

        ResponseEntity<String> r = reissue(student, c.submissionId(), OTHER_SHA);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(r)).isEqualTo(ErrorCodes.HASH_MISMATCH_AFTER_DEADLINE);
    }

    @Test
    void sameHashAfterTheDeadlineButInsideGraceStillGetsAUrl() {
        CommitResponse c = commit(student);
        clock.set(deadline.plusSeconds(900));

        ResponseEntity<String> r = reissue(student, c.submissionId(), SHA);

        assertThat(r.getStatusCode()).as("grace end itself is still inside grace").isEqualTo(HttpStatus.OK);
    }

    @Test
    void afterGraceTheAnswerIs410EvenWithTheRightHash() {
        CommitResponse c = commit(student);
        clock.set(deadline.plusSeconds(901));

        ResponseEntity<String> same = reissue(student, c.submissionId(), SHA);
        ResponseEntity<String> different = reissue(student, c.submissionId(), OTHER_SHA);

        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(code(same)).isEqualTo(ErrorCodes.GRACE_OVER);
        assertThat(different.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(code(different)).isEqualTo(ErrorCodes.GRACE_OVER);
    }

    @Test
    void aSubmissionThatIsNoLongerCommittedGets409WrongState() {
        CommitResponse c = commit(student);
        jdbc.update("UPDATE submissions SET status = 'UPLOADED' WHERE id = ?", c.submissionId());

        ResponseEntity<String> r = reissue(student, c.submissionId(), SHA);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(r)).isEqualTo(ErrorCodes.WRONG_STATE);
    }

    @Test
    void ownershipAndAuthenticationAreEnforced() {
        CommitResponse c = commit(student);

        assertThat(reissue(otherStudent, c.submissionId(), SHA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reissue(teacher, c.submissionId(), SHA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reissue(null, c.submissionId(), SHA).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> unknown = reissue(student, UUID.randomUUID(), SHA);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(unknown)).isEqualTo(ErrorCodes.SUBMISSION_NOT_FOUND);
    }

    @Test
    void aMalformedHashIsRejectedWith400() {
        CommitResponse c = commit(student);

        ResponseEntity<String> r = reissue(student, c.submissionId(), "ABC");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(r)).isEqualTo(ErrorCodes.VALIDATION_FAILED);
    }
}
