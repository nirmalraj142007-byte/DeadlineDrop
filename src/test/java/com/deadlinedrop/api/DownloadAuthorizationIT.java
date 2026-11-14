package com.deadlinedrop.api;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.dto.ReadDtos.DownloadUrlResponse;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Enrollment;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.Submission.Row;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.domain.Timing;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import com.deadlinedrop.repository.SubmissionRepository;
import com.deadlinedrop.repository.TransitionRepository;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.security.JwtService;
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.support.MutableClock;
import com.deadlinedrop.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, DownloadAuthorizationIT.TestClockConfig.class})
@TestPropertySource(properties = {
        "JWT_SECRET=dGVzdC1zZWNyZXQta2V5LXdpdGgtYXQtbGVhc3QtMzItYnl0ZXMtbG9uZw=="
})
class DownloadAuthorizationIT {

    private static final Path TEMP_ROOT;

    static {
        try {
            TEMP_ROOT = Files.createTempDirectory("dd-download-it-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        public MutableClock mutableClock() {
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
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final TransitionRepository transitionRepository;
    private final StoragePort storagePort;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    private User teacher;
    private User otherTeacher;
    private User student1;
    private User student2;
    private Course course;
    private Assignment assignment;
    private Instant deadline;

    @Autowired
    DownloadAuthorizationIT(
            TestRestTemplate rest,
            MutableClock clock,
            UserRepository userRepository,
            CourseRepository courseRepository,
            EnrollmentRepository enrollmentRepository,
            AssignmentRepository assignmentRepository,
            SubmissionRepository submissionRepository,
            TransitionRepository transitionRepository,
            StoragePort storagePort,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate) {
        this.rest = rest;
        this.clock = clock;
        this.userRepository = userRepository;
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.assignmentRepository = assignmentRepository;
        this.submissionRepository = submissionRepository;
        this.transitionRepository = transitionRepository;
        this.storagePort = storagePort;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void setUp() {
        cleanDatabase();
        deadline = Instant.parse("2026-11-20T23:59:59Z");
        clock.set(Instant.parse("2026-11-20T12:00:00Z"));

        teacher = userRepository.save(User.create("teacher@dd.local", passwordEncoder.encode("Secret123!"), Role.TEACHER, "Teacher"));
        otherTeacher = userRepository.save(User.create("otherteacher@dd.local", passwordEncoder.encode("Secret123!"), Role.TEACHER, "Other Teacher"));

        student1 = userRepository.save(User.create("alice@dd.local", passwordEncoder.encode("Secret123!"), Role.STUDENT, "Alice"));
        student2 = userRepository.save(User.create("bob@dd.local", passwordEncoder.encode("Secret123!"), Role.STUDENT, "Bob"));

        course = courseRepository.save(Course.create("CS101", "Systems", teacher.getId()));
        enrollmentRepository.save(Enrollment.enroll(course.getId(), student1.getId()));
        enrollmentRepository.save(Enrollment.enroll(course.getId(), student2.getId()));

        assignment = assignmentRepository.save(Assignment.create(
                course.getId(), "Lab 1", deadline, 900, 10_000_000L,
                List.of("application/pdf", "application/zip"), teacher.getId()));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM jobs");
        jdbcTemplate.update("DELETE FROM submission_events");
        jdbcTemplate.update("DELETE FROM submissions");
        jdbcTemplate.update("DELETE FROM baseline_submissions");
        enrollmentRepository.deleteAll();
        assignmentRepository.deleteAll();
        courseRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String rewriteToLocalPort(String signedUrl) throws Exception {
        URI uri = URI.create(signedUrl);
        return new URI("http", null, "localhost", port, uri.getPath(), uri.getQuery(), null).toString();
    }

    @Test
    @DisplayName("Single submission download: teacher gets signed URL for UPLOADED or VERIFIED, can fetch bytes")
    void singleSubmissionDownloadUrlSuccess() throws Exception {
        UUID subId = UUID.randomUUID();
        String objectPath = "a/" + assignment.getId() + "/s/" + student1.getId() + "/" + subId;
        byte[] payload = "PDF-CONTENT-ALICE".getBytes(StandardCharsets.UTF_8);
        storagePort.put(objectPath, "application/pdf", new ByteArrayInputStream(payload), payload.length);

        submissionRepository.insertCommitted(new Row(
                subId, assignment.getId(), student1.getId(), UUID.randomUUID(),
                "f".repeat(64), "1".repeat(64), payload.length, "application/pdf",
                objectPath, Status.COMMITTED, Timing.ON_TIME, clock.instant(), clock.instant(),
                null, null, null, null, null, clock.instant(), clock.instant()));
        transitionRepository.transition(subId, Status.COMMITTED, Status.UPLOADED, clock.instant());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.accessToken(jwtService, teacher));

        ResponseEntity<DownloadUrlResponse> response = rest.exchange(
                "/api/submissions/" + subId + "/download",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                DownloadUrlResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DownloadUrlResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.url()).contains("alice_" + subId + ".pdf");
        assertThat(body.expiresAt()).isAfter(clock.instant());

        // Perform GET using HttpClient to download the object directly from the storage server
        HttpRequest getReq = HttpRequest.newBuilder().uri(URI.create(rewriteToLocalPort(body.url()))).GET().build();
        HttpResponse<byte[]> getRes = httpClient.send(getReq, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(getRes.statusCode()).isEqualTo(200);
        assertThat(getRes.body()).isEqualTo(payload);

        // Transition to VERIFIED and verify download URL is still accessible
        transitionRepository.transition(subId, Status.UPLOADED, Status.VERIFIED, clock.instant());
        ResponseEntity<DownloadUrlResponse> verifiedResp = rest.exchange(
                "/api/submissions/" + subId + "/download",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                DownloadUrlResponse.class);
        assertThat(verifiedResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Single submission download fails with 409 NOT_UPLOADED if not in UPLOADED or VERIFIED status")
    void singleSubmissionDownloadNotUploaded() {
        UUID subId = UUID.randomUUID();
        String objectPath = "a/" + assignment.getId() + "/s/" + student1.getId() + "/" + subId;
        submissionRepository.insertCommitted(new Row(
                subId, assignment.getId(), student1.getId(), UUID.randomUUID(),
                "f".repeat(64), "2".repeat(64), 1024, "application/pdf",
                objectPath, Status.COMMITTED, Timing.ON_TIME, clock.instant(), clock.instant(),
                null, null, null, null, null, clock.instant(), clock.instant()));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.accessToken(jwtService, teacher));

        // When status is COMMITTED -> 409
        ResponseEntity<String> respCommitted = rest.exchange(
                "/api/submissions/" + subId + "/download",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(respCommitted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(respCommitted.getBody()).contains("NOT_UPLOADED");

        // When status is EXPIRED -> 409
        transitionRepository.transition(subId, Status.COMMITTED, Status.EXPIRED, clock.instant());
        ResponseEntity<String> respExpired = rest.exchange(
                "/api/submissions/" + subId + "/download",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(respExpired.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(respExpired.getBody()).contains("NOT_UPLOADED");
    }

    @Test
    @DisplayName("Single submission download forbidden for student and non-owner teacher")
    void singleSubmissionDownloadForbidden() {
        UUID subId = UUID.randomUUID();
        String objectPath = "a/" + assignment.getId() + "/s/" + student1.getId() + "/" + subId;
        submissionRepository.insertCommitted(new Row(
                subId, assignment.getId(), student1.getId(), UUID.randomUUID(),
                "f".repeat(64), "3".repeat(64), 1024, "application/pdf",
                objectPath, Status.COMMITTED, Timing.ON_TIME, clock.instant(), clock.instant(),
                null, null, null, null, null, clock.instant(), clock.instant()));
        transitionRepository.transition(subId, Status.COMMITTED, Status.UPLOADED, clock.instant());

        // Owning student -> 403
        HttpHeaders studentHeaders = new HttpHeaders();
        studentHeaders.setBearerAuth(TestTokens.accessToken(jwtService, student1));
        ResponseEntity<String> respStudent = rest.exchange(
                "/api/submissions/" + subId + "/download",
                HttpMethod.GET,
                new HttpEntity<>(studentHeaders),
                String.class);
        assertThat(respStudent.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Non-owner teacher -> 403
        HttpHeaders otherTeacherHeaders = new HttpHeaders();
        otherTeacherHeaders.setBearerAuth(TestTokens.accessToken(jwtService, otherTeacher));
        ResponseEntity<String> respOtherTeacher = rest.exchange(
                "/api/submissions/" + subId + "/download",
                HttpMethod.GET,
                new HttpEntity<>(otherTeacherHeaders),
                String.class);
        assertThat(respOtherTeacher.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Download all before graceEndsAt fails with 409 GRACE_NOT_OVER")
    void downloadAllBeforeGraceEndsAtFails() {
        // Clock is at 12:00, deadline is 23:59:59 + 900s
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.accessToken(jwtService, teacher));

        ResponseEntity<String> response = rest.exchange(
                "/api/assignments/" + assignment.getId() + "/download-all",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("GRACE_NOT_OVER");
    }

    @Test
    @DisplayName("Download all after graceEndsAt streams zip with live UPLOADED and VERIFIED submissions")
    void downloadAllAfterGraceEndsAtStreamsZip() throws Exception {
        byte[] payload1 = "ALICE-REPORT-PDF".getBytes(StandardCharsets.UTF_8);
        UUID sub1 = UUID.randomUUID();
        String path1 = "a/" + assignment.getId() + "/s/" + student1.getId() + "/" + sub1;
        storagePort.put(path1, "application/pdf", new ByteArrayInputStream(payload1), payload1.length);
        submissionRepository.insertCommitted(new Row(
                sub1, assignment.getId(), student1.getId(), UUID.randomUUID(),
                "f".repeat(64), "1".repeat(64), payload1.length, "application/pdf",
                path1, Status.COMMITTED, Timing.ON_TIME, clock.instant(), clock.instant(),
                null, null, null, null, null, clock.instant(), clock.instant()));
        transitionRepository.transition(sub1, Status.COMMITTED, Status.UPLOADED, clock.instant());

        byte[] payload2 = "BOB-CODE-ZIP".getBytes(StandardCharsets.UTF_8);
        UUID sub2 = UUID.randomUUID();
        String path2 = "a/" + assignment.getId() + "/s/" + student2.getId() + "/" + sub2;
        storagePort.put(path2, "application/zip", new ByteArrayInputStream(payload2), payload2.length);
        submissionRepository.insertCommitted(new Row(
                sub2, assignment.getId(), student2.getId(), UUID.randomUUID(),
                "f".repeat(64), "2".repeat(64), payload2.length, "application/zip",
                path2, Status.COMMITTED, Timing.ON_TIME, clock.instant(), clock.instant(),
                null, null, null, null, null, clock.instant(), clock.instant()));
        transitionRepository.transition(sub2, Status.COMMITTED, Status.UPLOADED, clock.instant());
        transitionRepository.transition(sub2, Status.UPLOADED, Status.VERIFIED, clock.instant());

        // Advance clock past graceEndsAt
        clock.set(assignment.graceEndsAt().plusSeconds(1));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.accessToken(jwtService, teacher));

        ResponseEntity<byte[]> response = rest.exchange(
                "/api/assignments/" + assignment.getId() + "/download-all",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/zip");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("assignment-" + assignment.getId() + "-submissions.zip");

        // Unzip and verify contents
        byte[] zipBytes = response.getBody();
        assertThat(zipBytes).isNotNull();

        Map<String, byte[]> unzipped = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                unzipped.put(entry.getName(), zis.readAllBytes());
                zis.closeEntry();
            }
        }

        assertThat(unzipped).hasSize(2);
        String aliceExpected = "alice_" + sub1 + ".pdf";
        String bobExpected = "bob_" + sub2 + ".zip";

        assertThat(unzipped).containsKey(aliceExpected);
        assertThat(unzipped.get(aliceExpected)).isEqualTo(payload1);

        assertThat(unzipped).containsKey(bobExpected);
        assertThat(unzipped.get(bobExpected)).isEqualTo(payload2);
    }

    @Test
    @DisplayName("Download all authorization: non-owner teacher gets 403, student gets 403, unauthenticated gets 401")
    void downloadAllAuthorizationEnforced() {
        clock.set(assignment.graceEndsAt().plusSeconds(10));

        // Non-owner teacher -> 403
        HttpHeaders otherTeacherHeaders = new HttpHeaders();
        otherTeacherHeaders.setBearerAuth(TestTokens.accessToken(jwtService, otherTeacher));
        ResponseEntity<String> respOther = rest.exchange(
                "/api/assignments/" + assignment.getId() + "/download-all",
                HttpMethod.GET,
                new HttpEntity<>(otherTeacherHeaders),
                String.class);
        assertThat(respOther.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Student -> 403
        HttpHeaders studentHeaders = new HttpHeaders();
        studentHeaders.setBearerAuth(TestTokens.accessToken(jwtService, student1));
        ResponseEntity<String> respStudent = rest.exchange(
                "/api/assignments/" + assignment.getId() + "/download-all",
                HttpMethod.GET,
                new HttpEntity<>(studentHeaders),
                String.class);
        assertThat(respStudent.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Unauthenticated -> 401
        ResponseEntity<String> respUnauth = rest.exchange(
                "/api/assignments/" + assignment.getId() + "/download-all",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class);
        assertThat(respUnauth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Missing assignment -> 404
        HttpHeaders teacherHeaders = new HttpHeaders();
        teacherHeaders.setBearerAuth(TestTokens.accessToken(jwtService, teacher));
        ResponseEntity<String> respMissing = rest.exchange(
                "/api/assignments/" + UUID.randomUUID() + "/download-all",
                HttpMethod.GET,
                new HttpEntity<>(teacherHeaders),
                String.class);
        assertThat(respMissing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
