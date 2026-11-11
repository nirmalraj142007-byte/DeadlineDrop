package com.deadlinedrop.jobs;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Enrollment;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.jobs.DrainService.DrainResult;
import com.deadlinedrop.jobs.ReconcilerSweep.ReconcileResult;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.security.JwtService;
import com.deadlinedrop.service.CommitService;
import com.deadlinedrop.service.CommitService.CommitCommand;
import com.deadlinedrop.service.CommitService.CommitResult;
import com.deadlinedrop.service.FinalizeService;
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.support.MutableClock;
import com.deadlinedrop.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transactional job queue end to end, against real Postgres and the real local storage server:
 * verify outcomes, retry backoff and give-up, two drainers sharing the queue, the reconciler sweep,
 * and who may call the internal endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JobsIT.ClockConfig.class})
class JobsIT {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-11-20T23:59:59Z");
    private static final int GRACE_SECONDS = 900;
    private static final int SIZE = 4096;
    private static final int STUDENTS = 20;
    private static final Duration BUDGET = Duration.ofSeconds(30);
    private static final String TOKEN = "test-internal-token-at-least-32-characters";
    private static final Path STORAGE_ROOT = tempDir();

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.of(NOW);
        }
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("dd.storage.server.enabled", () -> "true");
        registry.add("dd.storage.root", () -> STORAGE_ROOT.toAbsolutePath().toString());
    }

    private final MutableClock clock;
    private final UserRepository users;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final AssignmentRepository assignments;
    private final CommitService commitService;
    private final FinalizeService finalizeService;
    private final DrainService drainService;
    private final ReconcilerSweep reconcilerSweep;
    private final StoragePort storage;
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final int port;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private final List<User> students = new ArrayList<>();
    private UUID assignmentId;

    @Autowired
    JobsIT(MutableClock clock, UserRepository users, CourseRepository courses, EnrollmentRepository enrollments,
            AssignmentRepository assignments, CommitService commitService, FinalizeService finalizeService,
            DrainService drainService, ReconcilerSweep reconcilerSweep, StoragePort storage, JwtService jwtService,
            JdbcTemplate jdbc, ObjectMapper objectMapper, @LocalServerPort int port) {
        this.clock = clock;
        this.users = users;
        this.courses = courses;
        this.enrollments = enrollments;
        this.assignments = assignments;
        this.commitService = commitService;
        this.finalizeService = finalizeService;
        this.drainService = drainService;
        this.reconcilerSweep = reconcilerSweep;
        this.storage = storage;
        this.jwtService = jwtService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.port = port;
    }

    @BeforeEach
    void setUp() {
        clean();
        clock.set(NOW);
        User teacher = users.save(User.create("jobs-teacher@dd.local", "unused-hash", Role.TEACHER, "Jobs Teacher"));
        Course course = courses.save(Course.create("JOBS1", "Jobs course", teacher.getId()));
        students.clear();
        for (int i = 0; i < STUDENTS; i++) {
            User s = users.save(User.create("jobs-student-" + i + "@dd.local", "unused-hash", Role.STUDENT, "Jobs Student " + i));
            enrollments.save(Enrollment.enroll(course.getId(), s.getId()));
            students.add(s);
        }
        assignmentId = assignments.save(Assignment.create(course.getId(), "Jobs lab", DEADLINE, GRACE_SECONDS,
                1_048_576L, List.of("application/pdf"), teacher.getId())).getId();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // --- verify outcomes -------------------------------------------------------------------

    @Test
    void matchingBytesBecomeVerifiedAndTheJobIsDone() throws Exception {
        UUID id = uploaded(0, file(1));

        DrainResult result = drainService.drain(BUDGET);

        assertThat(result.processed()).isEqualTo(1);
        assertThat(result.retried()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(status(id)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("SELECT verified_at FROM submissions WHERE id = ?", Timestamp.class, id))
                .isEqualTo(Timestamp.from(NOW));
        assertThat(jobStatus(id)).isEqualTo("DONE");
        assertThat(verifierEvents(id, "VERIFIED")).isEqualTo(1);

        assertThat(drainService.drain(BUDGET).processed()).as("nothing left to drain").isZero();
        assertThat(verifierEvents(id, "VERIFIED")).isEqualTo(1);
    }

    @Test
    void bytesThatDoNotMatchTheCommittedHashAreRejected() throws Exception {
        byte[] committed = file(2);
        byte[] swapped = file(3); // same size, so finalize's size check passes; only the hash differs
        CommitResult c = commit(0, committed);
        put(c, swapped);
        assertThat(finalizeService.finalize(students.get(0).getId(), c.submissionId())).isEqualTo(Status.UPLOADED);

        DrainResult result = drainService.drain(BUDGET);

        assertThat(result.processed()).isEqualTo(1);
        assertThat(status(c.submissionId())).isEqualTo("REJECTED_HASH_MISMATCH");
        assertThat(jobStatus(c.submissionId())).isEqualTo("DONE");
        assertThat(verifierEvents(c.submissionId(), "REJECTED_HASH_MISMATCH")).isEqualTo(1);
        assertThat(verifierEvents(c.submissionId(), "VERIFIED")).isZero();
    }

    @Test
    void jobForASubmissionThatIsNoLongerUploadedIsDoneWithoutAChange() throws Exception {
        UUID v1 = uploaded(0, file(4));
        CommitResult v2 = commit(0, file(5)); // supersedes v1 before the verifier runs

        DrainResult result = drainService.drain(BUDGET);

        assertThat(result.processed()).isEqualTo(1);
        assertThat(status(v1)).isEqualTo("SUPERSEDED");
        assertThat(status(v2.submissionId())).isEqualTo("COMMITTED");
        assertThat(jobStatus(v1)).isEqualTo("DONE");
        assertThat(count("SELECT count(*) FROM submission_events WHERE actor = 'VERIFIER'")).isZero();
    }

    // --- retries ---------------------------------------------------------------------------

    @Test
    void failingJobBacksOffOneTwoFourEightMinutesThenFailsOnTheFifthAttempt() throws Exception {
        UUID id = uploaded(0, file(6));
        storage.delete(objectPath(id)); // storage.open now fails on every attempt

        for (int attempt = 1; attempt <= 4; attempt++) {
            Instant attemptAt = clock.instant();
            DrainResult result = drainService.drain(BUDGET);

            assertThat(result.retried()).as("attempt %d", attempt).isEqualTo(1);
            assertThat(result.processed()).isZero();
            assertThat(result.failed()).isZero();
            assertThat(jobInt(id, "attempts")).isEqualTo(attempt);
            assertThat(jobStatus(id)).isEqualTo("PENDING");
            Instant runAfter = jobRunAfter(id);
            assertThat(runAfter).as("backoff after attempt %d", attempt)
                    .isEqualTo(attemptAt.plus(Duration.ofMinutes(1L << (attempt - 1))));
            assertThat(jobLastError(id)).contains("Object not found");

            clock.set(runAfter.minusSeconds(1));
            DrainResult early = drainService.drain(BUDGET);
            assertThat(early.retried() + early.processed() + early.failed()).as("not due yet").isZero();

            clock.set(runAfter);
        }

        DrainResult last = drainService.drain(BUDGET);

        assertThat(last.failed()).isEqualTo(1);
        assertThat(last.retried()).isZero();
        assertThat(jobStatus(id)).isEqualTo("FAILED");
        assertThat(jobInt(id, "attempts")).isEqualTo(DrainService.MAX_ATTEMPTS);
        assertThat(status(id)).as("a failed job leaves the submission for a human").isEqualTo("UPLOADED");

        clock.advance(Duration.ofHours(1));
        DrainResult after = drainService.drain(BUDGET);
        assertThat(after.retried() + after.processed() + after.failed()).as("FAILED jobs are never claimed").isZero();
    }

    // --- concurrency -----------------------------------------------------------------------

    @Test
    void twoDrainersTogetherVerifyEachSubmissionExactlyOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < STUDENTS; i++) {
            ids.add(uploaded(i, file(100 + i)));
        }
        assertThat(count("SELECT count(*) FROM jobs WHERE status = 'PENDING'")).isEqualTo(STUDENTS);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<DrainResult> a = pool.submit(() -> {
                go.await();
                return drainService.drain(BUDGET);
            });
            Future<DrainResult> b = pool.submit(() -> {
                go.await();
                return drainService.drain(BUDGET);
            });
            go.countDown();
            DrainResult ra = a.get(60, TimeUnit.SECONDS);
            DrainResult rb = b.get(60, TimeUnit.SECONDS);

            assertThat(ra.processed() + rb.processed()).isEqualTo(STUDENTS);
            assertThat(ra.retried() + rb.retried() + ra.failed() + rb.failed()).isZero();
            assertThat(count("SELECT count(*) FROM jobs WHERE status = 'DONE'")).isEqualTo(STUDENTS);
            for (UUID id : ids) {
                assertThat(status(id)).isEqualTo("VERIFIED");
                assertThat(verifierEvents(id, "VERIFIED")).as("VERIFIED events for %s", id).isEqualTo(1);
            }
            assertThat(count("SELECT count(*) FROM submission_events WHERE actor = 'VERIFIER'")).isEqualTo(STUDENTS);
            System.out.printf("JobsIT: two drainers processed %d + %d = %d jobs, each submission verified once%n",
                    ra.processed(), rb.processed(), STUDENTS);
        } finally {
            pool.shutdownNow();
        }
    }

    // --- reconciler sweep ------------------------------------------------------------------

    @Test
    void sweepSettlesStaleCommitsSkipsFreshOnesAndExpiresAfterGrace() throws Exception {
        CommitResult withBytes = commit(0, file(7));
        put(withBytes, file(7));                     // browser died before finalize
        CommitResult noBytes = commit(1, file(8));   // browser died before the PUT
        clock.advance(Duration.ofMinutes(3));
        CommitResult fresh = commit(2, file(9));     // committed just now, still uploading

        ReconcileResult first = reconcilerSweep.run(clock.instant());

        assertThat(first).isEqualTo(new ReconcileResult(1, 0, 1));
        assertThat(status(withBytes.submissionId())).isEqualTo("UPLOADED");
        assertThat(count("SELECT count(*) FROM submission_events WHERE submission_id = ? AND actor = 'RECONCILER'",
                withBytes.submissionId())).isEqualTo(1);
        assertThat(jobStatus(withBytes.submissionId())).isEqualTo("PENDING");
        assertThat(status(noBytes.submissionId())).isEqualTo("COMMITTED");
        assertThat(status(fresh.submissionId())).isEqualTo("COMMITTED");

        assertThat(drainService.drain(BUDGET).processed()).isEqualTo(1);
        assertThat(status(withBytes.submissionId())).isEqualTo("VERIFIED");

        clock.set(DEADLINE.plusSeconds(GRACE_SECONDS + 1));
        ReconcileResult afterGrace = reconcilerSweep.run(clock.instant());

        assertThat(afterGrace).isEqualTo(new ReconcileResult(0, 2, 0));
        assertThat(status(noBytes.submissionId())).isEqualTo("EXPIRED");
        assertThat(status(fresh.submissionId())).isEqualTo("EXPIRED");
        assertThat(status(withBytes.submissionId())).isEqualTo("VERIFIED");
    }

    // --- who may call /internal/** ---------------------------------------------------------

    @Test
    void internalEndpointsNeedTheInternalToken() throws Exception {
        String studentJwt = TestTokens.accessToken(jwtService, students.get(0));
        for (String path : List.of("/internal/jobs/drain", "/internal/reconcile")) {
            HttpResponse<String> none = post(path, null, null);
            assertUnauthenticated(none);

            HttpResponse<String> wrong = post(path, "X-Internal-Token", TOKEN + "x");
            assertUnauthenticated(wrong);

            HttpResponse<String> jwt = post(path, "Authorization", "Bearer " + studentJwt);
            assertUnauthenticated(jwt);
        }

        UUID id = uploaded(0, file(10));
        HttpResponse<String> drained = post("/internal/jobs/drain", "X-Internal-Token", TOKEN);
        assertThat(drained.statusCode()).as(drained.body()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(drained.body());
        assertThat(body.get("processed").asInt()).isEqualTo(1);
        assertThat(body.has("retried")).isTrue();
        assertThat(body.has("failed")).isTrue();
        assertThat(body.has("ms")).isTrue();
        assertThat(status(id)).isEqualTo("VERIFIED");

        HttpResponse<String> reconciled = post("/internal/reconcile", "X-Internal-Token", TOKEN);
        assertThat(reconciled.statusCode()).as(reconciled.body()).isEqualTo(200);
        JsonNode r = objectMapper.readTree(reconciled.body());
        assertThat(r.get("settled").asInt()).isZero();
        assertThat(r.get("expired").asInt()).isZero();
        assertThat(r.get("waiting").asInt()).isZero();
    }

    private void assertUnauthenticated(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/problem+json"));
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.get("requestId").asText()).isNotBlank();
    }

    // --- helpers ---------------------------------------------------------------------------

    private CommitResult commit(int student, byte[] bytes) {
        return commitService.commit(new CommitCommand(students.get(student).getId(), assignmentId, UUID.randomUUID(),
                sha256Hex(bytes), bytes.length, "application/pdf", clock.instant()));
    }

    /** Commit, PUT the same bytes, finalize: an UPLOADED submission with one PENDING VERIFY job. */
    private UUID uploaded(int student, byte[] bytes) throws Exception {
        CommitResult c = commit(student, bytes);
        put(c, bytes);
        assertThat(finalizeService.finalize(students.get(student).getId(), c.submissionId())).isEqualTo(Status.UPLOADED);
        return c.submissionId();
    }

    private void put(CommitResult c, byte[] bytes) throws IOException, InterruptedException, URISyntaxException {
        URI signed = URI.create(c.signedUrl().url());
        URI local = new URI("http", null, "localhost", port, signed.getPath(), signed.getQuery(), null);
        HttpRequest.Builder put = HttpRequest.newBuilder(local).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        c.signedUrl().headers().forEach(put::header);
        HttpResponse<String> response = http.send(put.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("PUT: %s", response.body()).isEqualTo(200);
    }

    private HttpResponse<String> post(String path, String header, String value) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (header != null) {
            request.header(header, value);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status::text FROM submissions WHERE id = ?", String.class, id);
    }

    private String objectPath(UUID id) {
        return jdbc.queryForObject("SELECT object_path FROM submissions WHERE id = ?", String.class, id);
    }

    private String jobStatus(UUID id) {
        return jdbc.queryForObject("SELECT status::text FROM jobs WHERE submission_id = ?", String.class, id);
    }

    private int jobInt(UUID id, String column) {
        Integer n = jdbc.queryForObject("SELECT " + column + " FROM jobs WHERE submission_id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    private Instant jobRunAfter(UUID id) {
        return jdbc.queryForObject("SELECT run_after FROM jobs WHERE submission_id = ?", Timestamp.class, id).toInstant();
    }

    private String jobLastError(UUID id) {
        return jdbc.queryForObject("SELECT last_error FROM jobs WHERE submission_id = ?", String.class, id);
    }

    private int verifierEvents(UUID id, String to) {
        return count("SELECT count(*) FROM submission_events WHERE submission_id = ? AND actor = 'VERIFIER' "
                + "AND from_status = 'UPLOADED' AND to_status = ?::submission_status", id, to);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
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

    /** {@code SIZE} bytes whose content (and so hash) depends on {@code seed}. */
    private static byte[] file(int seed) {
        byte[] bytes = new byte[SIZE];
        bytes[0] = (byte) seed;
        bytes[1] = (byte) (seed >> 8);
        return bytes;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK specification", e);
        }
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("dd-jobs-it-");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create a temporary storage root", e);
        }
    }
}
