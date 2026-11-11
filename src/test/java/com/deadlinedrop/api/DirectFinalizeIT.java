package com.deadlinedrop.api;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.dto.BaselineDtos.BaselineSubmissionResponse;
import com.deadlinedrop.api.dto.BaselineDtos.DirectUploadRequest;
import com.deadlinedrop.api.dto.BaselineDtos.DirectUploadResponse;
import com.deadlinedrop.api.dto.CommitDtos.CommitRequest;
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
import com.deadlinedrop.storage.StoragePort;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"test", "direct-finalize"})
@Import({TestcontainersConfiguration.class, DirectFinalizeIT.TestClockConfig.class})
@TestPropertySource(properties = {
        "JWT_SECRET=dGVzdC1zZWNyZXQta2V5LXdpdGgtYXQtbGVhc3QtMzItYnl0ZXMtbG9uZw=="
})
class DirectFinalizeIT {

    private static final Path TEMP_ROOT;
    private static final long MAX_BYTES = 1_048_576L;

    static {
        try {
            TEMP_ROOT = Files.createTempDirectory("dd-direct-it-");
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

    private final TestRestTemplate restTemplate;
    private final MutableClock clock;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final AssignmentRepository assignmentRepository;
    private final StoragePort storagePort;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    private User student;
    private User otherEnrolled;
    private User outsider;
    private Assignment assignment;
    private Instant deadline;

    @Autowired
    DirectFinalizeIT(
            TestRestTemplate restTemplate,
            MutableClock clock,
            UserRepository userRepository,
            CourseRepository courseRepository,
            EnrollmentRepository enrollmentRepository,
            AssignmentRepository assignmentRepository,
            StoragePort storagePort,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.clock = clock;
        this.userRepository = userRepository;
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.assignmentRepository = assignmentRepository;
        this.storagePort = storagePort;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void setUp() {
        cleanDatabase();
        deadline = Instant.parse("2026-11-20T23:59:59Z");
        clock.set(Instant.parse("2026-11-20T12:00:00Z"));

        User teacher = userRepository.save(User.create(
                "teacher@dd.local", passwordEncoder.encode("Password123!"), Role.TEACHER, "Prof. Smith"));
        student = userRepository.save(User.create(
                "student1@dd.local", passwordEncoder.encode("Password123!"), Role.STUDENT, "Student One"));
        otherEnrolled = userRepository.save(User.create(
                "student2@dd.local", passwordEncoder.encode("Password123!"), Role.STUDENT, "Student Two"));
        outsider = userRepository.save(User.create(
                "student3@dd.local", passwordEncoder.encode("Password123!"), Role.STUDENT, "Student Three"));

        Course course = courseRepository.save(Course.create("CS101", "Introduction to Systems", teacher.getId()));
        enrollmentRepository.save(Enrollment.enroll(course.getId(), student.getId()));
        enrollmentRepository.save(Enrollment.enroll(course.getId(), otherEnrolled.getId()));

        assignment = assignmentRepository.save(Assignment.create(
                course.getId(), "Lab 1", deadline, 900, MAX_BYTES,
                List.of("application/pdf", "application/zip"), teacher.getId()));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM submission_events");
        jdbcTemplate.update("DELETE FROM submissions");
        jdbcTemplate.update("DELETE FROM baseline_submissions");
        enrollmentRepository.deleteAll();
        assignmentRepository.deleteAll();
        courseRepository.deleteAll();
        userRepository.deleteAll();
    }

    private HttpHeaders headers(User as) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (as != null) {
            String token = TestTokens.accessToken(jwtService, as);
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private ResponseEntity<String> createUpload(User as, long size, String contentType) {
        return restTemplate.postForEntity(
                "/api/assignments/" + assignment.getId() + "/direct-uploads",
                new HttpEntity<>(new DirectUploadRequest(size, contentType), headers(as)),
                String.class);
    }

    private ResponseEntity<String> finalizeUpload(User as, UUID uploadId) {
        return restTemplate.postForEntity(
                "/api/assignments/" + assignment.getId() + "/direct-uploads/" + uploadId + "/finalize",
                new HttpEntity<>(headers(as)),
                String.class);
    }

    private DirectUploadResponse parseUpload(ResponseEntity<String> response) {
        return objectMapper.readValue(response.getBody(), DirectUploadResponse.class);
    }

    private BaselineSubmissionResponse parseSubmission(ResponseEntity<String> response) {
        return objectMapper.readValue(response.getBody(), BaselineSubmissionResponse.class);
    }

    private String errorCode(ResponseEntity<String> response) {
        return objectMapper.readTree(response.getBody()).get("code").asText();
    }

    private String rewriteToLocalPort(String signedUrl) throws URISyntaxException {
        URI uri = URI.create(signedUrl);
        return new URI("http", null, "localhost", port, uri.getPath(), uri.getQuery(), null).toString();
    }

    private int putBytes(DirectUploadResponse upload, byte[] bytes) throws Exception {
        HttpRequest.Builder put = HttpRequest.newBuilder()
                .uri(URI.create(rewriteToLocalPort(upload.uploadUrl())))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        upload.uploadHeaders().forEach(put::header);
        return httpClient.send(put.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private String pathFor(User owner, UUID uploadId) {
        return "a/" + assignment.getId() + "/s/" + owner.getId() + "/" + uploadId;
    }

    private int baselineCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM baseline_submissions", Integer.class);
    }

    @Test
    void createUploadReturns201WithSignedUrlAndWritesNothingToTheDatabase() {
        ResponseEntity<String> response = createUpload(student, 1024, "application/pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        DirectUploadResponse body = parseUpload(response);
        assertThat(body.uploadId()).isNotNull();
        assertThat(body.uploadUrl())
                .contains("/storage/a/" + assignment.getId() + "/s/" + student.getId() + "/" + body.uploadId());
        assertThat(body.uploadHeaders()).containsEntry("Content-Type", "application/pdf");
        assertThat(body.urlExpiresAt()).isEqualTo(clock.instant().plusSeconds(15 * 60));
        assertThat(baselineCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM submissions", Integer.class)).isZero();
    }

    @Test
    void putThenFinalizeReturns200StampedFinalizeAndOnTime() throws Exception {
        byte[] bytes = new byte[1024];
        bytes[0] = '%';
        DirectUploadResponse upload = parseUpload(createUpload(student, bytes.length, "application/pdf"));
        assertThat(putBytes(upload, bytes)).isEqualTo(200);

        Instant finalizeTime = Instant.parse("2026-11-20T12:05:00.123Z");
        clock.set(finalizeTime);
        ResponseEntity<String> response = finalizeUpload(student, upload.uploadId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BaselineSubmissionResponse body = parseSubmission(response);
        assertThat(body.stampSource()).isEqualTo("FINALIZE");
        assertThat(body.timing()).isEqualTo("ON_TIME");
        assertThat(body.submittedAt()).isEqualTo(finalizeTime);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT student_id, idempotency_key, object_path, size_bytes, stamp_source FROM baseline_submissions WHERE id = ?",
                body.submissionId());
        assertThat(row.get("student_id")).isEqualTo(student.getId());
        assertThat(row.get("idempotency_key")).isEqualTo(upload.uploadId());
        assertThat(row.get("object_path")).isEqualTo(pathFor(student, upload.uploadId()));
        assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(bytes.length);
        assertThat(row.get("stamp_source")).isEqualTo("FINALIZE");
        try (InputStream in = storagePort.open(pathFor(student, upload.uploadId()))) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void stampIsTakenAtFinalizeSoASlowFinalizeAfterTheDeadlineIsLate() throws Exception {
        // The behaviour arm C exists to fix: the stamp follows the bytes, not the click.
        clock.set(deadline.minusSeconds(2));
        DirectUploadResponse upload = parseUpload(createUpload(student, 1024, "application/pdf"));
        assertThat(putBytes(upload, new byte[1024])).isEqualTo(200);

        clock.set(deadline.plusSeconds(3));
        BaselineSubmissionResponse body = parseSubmission(finalizeUpload(student, upload.uploadId()));

        assertThat(body.timing()).isEqualTo("LATE");
        assertThat(body.submittedAt()).isEqualTo(deadline.plusSeconds(3));
        assertThat(body.stampSource()).isEqualTo("FINALIZE");
    }

    @Test
    void finalizingTwiceReturnsTheOriginalRowAndOriginalStamp() throws Exception {
        DirectUploadResponse upload = parseUpload(createUpload(student, 1024, "application/pdf"));
        assertThat(putBytes(upload, new byte[1024])).isEqualTo(200);
        BaselineSubmissionResponse first = parseSubmission(finalizeUpload(student, upload.uploadId()));

        clock.advance(Duration.ofSeconds(30));
        ResponseEntity<String> again = finalizeUpload(student, upload.uploadId());

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        BaselineSubmissionResponse replay = parseSubmission(again);
        assertThat(replay.submissionId()).isEqualTo(first.submissionId());
        assertThat(replay.submittedAt()).isEqualTo(first.submittedAt());
        assertThat(baselineCount()).isEqualTo(1);
    }

    @Test
    void finalizeWithoutBytesReturns404BytesNotFound() {
        DirectUploadResponse upload = parseUpload(createUpload(student, 1024, "application/pdf"));

        ResponseEntity<String> response = finalizeUpload(student, upload.uploadId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(response)).isEqualTo(ErrorCodes.BYTES_NOT_FOUND);
        assertThat(baselineCount()).isZero();
    }

    @Test
    void anotherStudentCannotFinalizeSomeoneElsesObject() throws Exception {
        DirectUploadResponse upload = parseUpload(createUpload(student, 1024, "application/pdf"));
        assertThat(putBytes(upload, new byte[1024])).isEqualTo(200);

        ResponseEntity<String> response = finalizeUpload(otherEnrolled, upload.uploadId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(response)).isEqualTo(ErrorCodes.BYTES_NOT_FOUND);
        assertThat(baselineCount()).isZero();
        assertThat(storagePort.stat(pathFor(student, upload.uploadId()))).as("owner's object is untouched").isPresent();
    }

    @Test
    void oversizedObjectIsDeletedAndReturns422SizeMismatch() {
        UUID uploadId = UUID.randomUUID();
        byte[] tooBig = new byte[(int) MAX_BYTES + 1];
        storagePort.put(pathFor(student, uploadId), "application/pdf", new ByteArrayInputStream(tooBig), tooBig.length);

        ResponseEntity<String> response = finalizeUpload(student, uploadId);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(errorCode(response)).isEqualTo(ErrorCodes.SIZE_MISMATCH);
        assertThat(storagePort.stat(pathFor(student, uploadId))).isEmpty();
        assertThat(baselineCount()).isZero();
    }

    @Test
    void disallowedContentTypeReturns415() {
        ResponseEntity<String> response = createUpload(student, 1024, "text/plain");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(errorCode(response)).isEqualTo(ErrorCodes.TYPE_NOT_ALLOWED);
    }

    @Test
    void declaredSizeAboveMaximumReturns413() {
        ResponseEntity<String> response = createUpload(student, MAX_BYTES + 1, "application/pdf");

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(errorCode(response)).isEqualTo(ErrorCodes.FILE_TOO_LARGE);
    }

    @Test
    void invalidBodyReturns400ValidationFailed() {
        ResponseEntity<String> response = createUpload(student, 0, "application/pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(response)).isEqualTo(ErrorCodes.VALIDATION_FAILED);
    }

    @Test
    void studentNotEnrolledGets403OnBothEndpoints() {
        ResponseEntity<String> create = createUpload(outsider, 1024, "application/pdf");
        ResponseEntity<String> fin = finalizeUpload(outsider, UUID.randomUUID());

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCode(create)).isEqualTo(ErrorCodes.NOT_ENROLLED);
        assertThat(fin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unauthenticatedRequestGets401() {
        ResponseEntity<String> response = createUpload(null, 1024, "application/pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void armCCommitEndpointIsNotAvailableUnderThisProfile() {
        HttpHeaders h = headers(student);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/assignments/" + assignment.getId() + "/commits",
                new HttpEntity<>(new CommitRequest("a".repeat(64), 1024, "application/pdf"), h),
                String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
    }
}
