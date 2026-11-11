package com.deadlinedrop.api;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.dto.AuthDtos.TokenResponse;
import com.deadlinedrop.api.dto.CommitDtos.CommitRequest;
import com.deadlinedrop.api.dto.CommitDtos.CommitResponse;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CommitIT.TestClockConfig.class})
@TestPropertySource(properties = {
        "JWT_SECRET=dGVzdC1zZWNyZXQta2V5LXdpdGgtYXQtbGVhc3QtMzItYnl0ZXMtbG9uZw=="
})
class CommitIT {

    private static final Path TEMP_ROOT;
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    static {
        try {
            TEMP_ROOT = Files.createTempDirectory("dd-commit-it-");
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

    private User teacher;
    private User student;
    private User outsider;
    private Assignment assignment;
    private Instant deadline;

    @Autowired
    CommitIT(
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
        clock.set(Instant.parse("2026-11-20T12:00:00.250Z"));

        teacher = userRepository.save(User.create(
                "teacher@dd.local", passwordEncoder.encode("Password123!"), Role.TEACHER, "Prof. Smith"));
        student = userRepository.save(User.create(
                "student1@dd.local", passwordEncoder.encode("Password123!"), Role.STUDENT, "Student One"));
        outsider = userRepository.save(User.create(
                "student2@dd.local", passwordEncoder.encode("Password123!"), Role.STUDENT, "Student Two"));

        Course course = courseRepository.save(Course.create("CS101", "Introduction to Systems", teacher.getId()));
        enrollmentRepository.save(Enrollment.enroll(course.getId(), student.getId()));

        assignment = assignmentRepository.save(Assignment.create(
                course.getId(), "Lab 1", deadline, 900, 1_048_576L,
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

    private ResponseEntity<String> post(User as, UUID key, Object body) {
        return post(assignment.getId(), as, key, body);
    }

    private ResponseEntity<String> post(UUID assignmentId, User as, UUID key, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (as != null) {
            TokenResponse token = jwtService.issue(as);
            headers.setBearerAuth(token.accessToken());
        }
        if (key != null) {
            headers.set("Idempotency-Key", key.toString());
        }
        return restTemplate.postForEntity(
                "/api/assignments/" + assignmentId + "/commits", new HttpEntity<>(body, headers), String.class);
    }

    private CommitResponse parse(ResponseEntity<String> response) {
        return objectMapper.readValue(response.getBody(), CommitResponse.class);
    }

    private String errorCode(ResponseEntity<String> response) {
        return objectMapper.readTree(response.getBody()).get("code").asText();
    }

    private static CommitRequest pdf(String sha) {
        return new CommitRequest(sha, 1024, "application/pdf");
    }

    private Map<String, Object> rowOf(UUID id) {
        return jdbcTemplate.queryForMap(
                "SELECT status::text AS status, timing::text AS timing, committed_at, db_committed_at, "
                        + "superseded_by, object_path, sha256, size_bytes FROM submissions WHERE id = ?", id);
    }

    private String rewriteToLocalPort(String signedUrl) throws URISyntaxException {
        URI uri = URI.create(signedUrl);
        return new URI("http", null, "localhost", port, uri.getPath(), uri.getQuery(), null).toString();
    }

    @Test
    void newCommitReturns201WithStampSignedUrlAndServerTiming() throws Exception {
        Instant arrival = Instant.parse("2026-11-20T12:34:56.789Z");
        clock.set(arrival);
        UUID key = UUID.randomUUID();

        ResponseEntity<String> response = post(student, key, pdf(SHA_A));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        CommitResponse body = parse(response);
        assertThat(body.committedAt()).isEqualTo(arrival);
        assertThat(body.timing()).isEqualTo("ON_TIME");
        assertThat(body.status()).isEqualTo("COMMITTED");
        assertThat(body.uploadUrl()).contains("/storage/a/" + assignment.getId() + "/s/" + student.getId() + "/" + body.submissionId());
        assertThat(body.uploadHeaders()).containsEntry("Content-Type", "application/pdf");
        assertThat(body.urlExpiresAt()).isEqualTo(arrival.plusSeconds(15 * 60));

        assertThat(response.getHeaders().getFirst("Server-Timing"))
                .matches("app;dur=\\d+, db;dur=\\d+, sign;dur=\\d+");

        Map<String, Object> row = rowOf(body.submissionId());
        assertThat(((java.sql.Timestamp) row.get("committed_at")).toInstant()).isEqualTo(arrival);
        assertThat(row.get("db_committed_at")).as("database clock kept separately for audit").isNotNull();
        assertThat(row.get("status")).isEqualTo("COMMITTED");
        assertThat(row.get("sha256")).isEqualTo(SHA_A);
        assertThat(row.get("object_path")).isEqualTo(
                "a/" + assignment.getId() + "/s/" + student.getId() + "/" + body.submissionId());

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT from_status::text AS f, to_status::text AS t, actor::text AS a FROM submission_events WHERE submission_id = ?",
                body.submissionId());
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.get("f")).isNull();
            assertThat(e.get("t")).isEqualTo("COMMITTED");
            assertThat(e.get("a")).isEqualTo("COMMIT");
        });
    }

    @Test
    void returnedUrlAcceptsARealPutOfTheBytes() throws Exception {
        byte[] bytes = new byte[1024];
        bytes[0] = '%';
        ResponseEntity<String> response = post(student, UUID.randomUUID(), pdf(SHA_A));
        CommitResponse body = parse(response);

        HttpRequest.Builder put = HttpRequest.newBuilder()
                .uri(URI.create(rewriteToLocalPort(body.uploadUrl())))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        body.uploadHeaders().forEach(put::header);
        HttpResponse<String> uploaded = httpClient.send(put.build(), HttpResponse.BodyHandlers.ofString());

        assertThat(uploaded.statusCode()).isEqualTo(200);
        String objectPath = (String) rowOf(body.submissionId()).get("object_path");
        assertThat(storagePort.stat(objectPath)).isPresent();
        try (InputStream in = storagePort.open(objectPath)) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void sameKeyAndSameBodyReplaysWith200AndWritesNothing() {
        UUID key = UUID.randomUUID();
        CommitResponse first = parse(post(student, key, pdf(SHA_A)));
        clock.advance(java.time.Duration.ofSeconds(30));

        ResponseEntity<String> again = post(student, key, pdf(SHA_A));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        CommitResponse replay = parse(again);
        assertThat(replay.submissionId()).isEqualTo(first.submissionId());
        assertThat(replay.committedAt()).as("the original stamp is kept").isEqualTo(first.committedAt());
        assertThat(replay.uploadUrl()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM submissions", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM submission_events", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyReturns409() {
        UUID key = UUID.randomUUID();
        post(student, key, pdf(SHA_A));

        ResponseEntity<String> response = post(student, key, pdf(SHA_B));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(response)).isEqualTo(ErrorCodes.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void disallowedContentTypeReturns415() {
        ResponseEntity<String> response = post(student, UUID.randomUUID(),
                new CommitRequest(SHA_A, 1024, "image/png"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(errorCode(response)).isEqualTo(ErrorCodes.TYPE_NOT_ALLOWED);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM submissions", Integer.class)).isZero();
    }

    @Test
    void oversizedFileReturns413() {
        ResponseEntity<String> response = post(student, UUID.randomUUID(),
                new CommitRequest(SHA_A, 1_048_577L, "application/pdf"));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(errorCode(response)).isEqualTo(ErrorCodes.FILE_TOO_LARGE);
    }

    @Test
    void exactlyMaxSizeIsAccepted() {
        ResponseEntity<String> response = post(student, UUID.randomUUID(),
                new CommitRequest(SHA_A, 1_048_576L, "application/pdf"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void commitAfterDeadlineIsAcceptedAndMarkedLate() {
        clock.set(deadline.plusSeconds(60));

        ResponseEntity<String> response = post(student, UUID.randomUUID(), pdf(SHA_A));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        CommitResponse body = parse(response);
        assertThat(body.timing()).isEqualTo("LATE");
        assertThat(body.status()).isEqualTo("COMMITTED");
    }

    @Test
    void commitExactlyAtDeadlineIsOnTime() {
        clock.set(deadline);

        assertThat(parse(post(student, UUID.randomUUID(), pdf(SHA_A))).timing()).isEqualTo("ON_TIME");
    }

    @Test
    void secondCommitSupersedesTheFirstAndLinksIt() {
        CommitResponse first = parse(post(student, UUID.randomUUID(), pdf(SHA_A)));
        clock.advance(java.time.Duration.ofMinutes(5));
        ResponseEntity<String> secondResponse = post(student, UUID.randomUUID(), pdf(SHA_B));

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        CommitResponse second = parse(secondResponse);

        Map<String, Object> oldRow = rowOf(first.submissionId());
        assertThat(oldRow.get("status")).isEqualTo("SUPERSEDED");
        assertThat(oldRow.get("superseded_by")).isEqualTo(second.submissionId());
        assertThat(rowOf(second.submissionId()).get("status")).isEqualTo("COMMITTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM submissions WHERE status NOT IN ('EXPIRED','SUPERSEDED')", Integer.class))
                .isEqualTo(1);

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT from_status::text AS f, to_status::text AS t, actor::text AS a FROM submission_events WHERE submission_id = ? ORDER BY id",
                first.submissionId());
        assertThat(events).hasSize(2);
        assertThat(events.get(1).get("f")).isEqualTo("COMMITTED");
        assertThat(events.get(1).get("t")).isEqualTo("SUPERSEDED");
        assertThat(events.get(1).get("a")).isEqualTo("NEW_COMMIT");
    }

    @Test
    void concurrentCommitsWithDifferentKeysLeaveExactlyOneLiveSubmission() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String sha = String.valueOf(i).repeat(64);
            Callable<ResponseEntity<String>> task = () -> {
                start.await();
                return post(student, UUID.randomUUID(), pdf(sha));
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int created = 0;
        for (Future<ResponseEntity<String>> f : results) {
            ResponseEntity<String> r = f.get();
            assertThat(r.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
            if (r.getStatusCode() == HttpStatus.CREATED) {
                created++;
            }
        }
        pool.shutdown();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM submissions WHERE status NOT IN ('EXPIRED','SUPERSEDED')", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM submissions", Integer.class)).isEqualTo(created);
    }

    @Test
    void concurrentRetriesWithTheSameKeyAllSeeOneSubmission() throws Exception {
        UUID key = UUID.randomUUID();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return post(student, key, pdf(SHA_A));
            }));
        }
        start.countDown();
        List<UUID> ids = new ArrayList<>();
        for (Future<ResponseEntity<String>> f : results) {
            ResponseEntity<String> r = f.get();
            assertThat(r.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
            ids.add(parse(r).submissionId());
        }
        pool.shutdown();

        assertThat(ids).allMatch(id -> id.equals(ids.get(0)));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM submissions", Integer.class)).isEqualTo(1);
    }

    @Test
    void studentNotEnrolledGets403() {
        ResponseEntity<String> response = post(outsider, UUID.randomUUID(), pdf(SHA_A));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCode(response)).isEqualTo(ErrorCodes.NOT_ENROLLED);
    }

    @Test
    void unknownAssignmentGets404() {
        ResponseEntity<String> response = post(UUID.randomUUID(), student, UUID.randomUUID(), pdf(SHA_A));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(response)).isEqualTo(ErrorCodes.ASSIGNMENT_NOT_FOUND);
    }

    @Test
    void teacherGets403AndAnonymousGets401() {
        assertThat(post(teacher, UUID.randomUUID(), pdf(SHA_A)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(null, UUID.randomUUID(), pdf(SHA_A)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void malformedBodiesAndMissingKeyGet400() {
        assertThat(post(student, UUID.randomUUID(), new CommitRequest("XYZ", 1024, "application/pdf"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(student, UUID.randomUUID(), new CommitRequest(SHA_A, 0, "application/pdf"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(student, UUID.randomUUID(), new CommitRequest(SHA_A, 10, " "))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> noKey = post(student, null, pdf(SHA_A));
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(noKey)).isEqualTo(ErrorCodes.MISSING_IDEMPOTENCY_KEY);
    }
}
