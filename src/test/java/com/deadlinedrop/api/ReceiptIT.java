package com.deadlinedrop.api;

import com.deadlinedrop.api.dto.CommitDtos.CommitRequest;
import com.deadlinedrop.api.dto.CommitDtos.CommitResponse;
import com.deadlinedrop.api.dto.CommitDtos.FinalizeRequest;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Enrollment;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.jobs.DrainService;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.security.JwtService;
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.support.MutableClock;
import com.deadlinedrop.support.TestTokens;
import com.zaxxer.hikari.HikariDataSource;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signed commit receipts against a real outage. Postgres is real and every connection goes through
 * Toxiproxy; disabling the proxy is the outage. Storage is the in-process local storage server, so
 * the PUT really lands while the database is down. The clock is a MutableClock so the deadline and
 * grace window can be crossed between commit and finalize.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import(ReceiptIT.TestClockConfig.class)
class ReceiptIT {

    private static final int PROXY_PORT = 8666;
    private static final Instant DEADLINE = Instant.parse("2026-11-20T23:59:59Z");
    private static final int GRACE_SECONDS = 900;
    private static final byte[] FILE = "%PDF-1.7 receipt test file\n".repeat(40).getBytes(StandardCharsets.UTF_8);
    private static final String SHA = sha256(FILE);

    private static final Network NETWORK = Network.newNetwork();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0")
            .withNetwork(NETWORK);
    private static final Proxy DATABASE_PROXY;
    private static final Path STORAGE_ROOT;

    static {
        POSTGRES.start();
        TOXIPROXY.start();
        try {
            DATABASE_PROXY = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
                    .createProxy("postgres", "0.0.0.0:" + PROXY_PORT, "postgres:5432");
            STORAGE_ROOT = Files.createTempDirectory("dd-receipt-it-");
        } catch (IOException e) {
            throw new UncheckedIOException("could not set up the Toxiproxy route to Postgres", e);
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.of(Instant.parse("2026-11-20T23:59:50Z"));
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + TOXIPROXY.getHost() + ":"
                + TOXIPROXY.getMappedPort(PROXY_PORT) + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("dd.storage.server.enabled", () -> "true");
        registry.add("dd.storage.root", () -> STORAGE_ROOT.toAbsolutePath().toString());
    }

    @AfterAll
    static void stopContainers() {
        TOXIPROXY.stop();
        POSTGRES.stop();
        NETWORK.close();
    }

    private final TestRestTemplate rest;
    private final ObjectMapper objectMapper;
    private final JwtService jwtService;
    private final MutableClock clock;
    private final UserRepository users;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final AssignmentRepository assignments;
    private final JdbcTemplate jdbc;
    private final StoragePort storage;
    private final DrainService drainService;
    private final DataSource dataSource;
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    private User student;
    private User otherStudent;
    private Course course;
    private Assignment assignment;

    @Autowired
    ReceiptIT(TestRestTemplate rest, ObjectMapper objectMapper, JwtService jwtService, MutableClock clock,
              UserRepository users, CourseRepository courses, EnrollmentRepository enrollments,
              AssignmentRepository assignments, JdbcTemplate jdbc, StoragePort storage, DrainService drainService,
              DataSource dataSource) {
        this.rest = rest;
        this.objectMapper = objectMapper;
        this.jwtService = jwtService;
        this.clock = clock;
        this.users = users;
        this.courses = courses;
        this.enrollments = enrollments;
        this.assignments = assignments;
        this.jdbc = jdbc;
        this.storage = storage;
        this.drainService = drainService;
        this.dataSource = dataSource;
    }

    @BeforeEach
    void setUp() {
        clean();
        clock.set(Instant.parse("2026-11-20T23:59:50Z"));
        User teacher = users.save(User.create("rc-teacher@dd.local", "unused-hash", Role.TEACHER, "Receipt Teacher"));
        student = users.save(User.create("rc-student1@dd.local", "unused-hash", Role.STUDENT, "Receipt One"));
        otherStudent = users.save(User.create("rc-student2@dd.local", "unused-hash", Role.STUDENT, "Receipt Two"));
        course = courses.save(Course.create("RCT101", "Receipt course", teacher.getId()));
        enrollments.save(Enrollment.enroll(course.getId(), student.getId()));
        enrollments.save(Enrollment.enroll(course.getId(), otherStudent.getId()));
        assignment = assignments.save(Assignment.create(course.getId(), "Lab 1", DEADLINE, GRACE_SECONDS,
                1_048_576L, List.of("application/pdf"), teacher.getId()));
    }

    @AfterEach
    void tearDown() throws IOException {
        DATABASE_PROXY.enable();
        clean();
    }

    // --- the outage path end to end ------------------------------------------------------------

    @Test
    void commitDuringOutageGetsAReceiptAndFinalizeAfterRecoveryRecordsTheArrivalStamp() {
        Instant arrival = clock.instant();

        // Twice, because a dead database surfaces two ways (see DatabaseDownIT): a connection that
        // dies mid-transaction, or a pool timeout. Both must produce a receipt, not a 503.
        List<ResponseEntity<String>> commits = duringOutage(() -> List.of(commitRaw(student), commitRaw(student)));
        for (ResponseEntity<String> r : commits) {
            assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.CREATED);
        }
        CommitResponse issued = read(commits.get(1), CommitResponse.class);
        assertThat(issued.status()).isEqualTo("RECEIPT_ISSUED");
        assertThat(issued.timing()).isEqualTo("PENDING");
        assertThat(issued.committedAt()).isEqualTo(arrival);
        assertThat(issued.receipt()).isNotBlank();
        assertThat(issued.uploadUrl()).isNotBlank();
        assertThat(count("SELECT count(*) FROM submissions")).isZero();

        // The bytes move while the database is still down; finalize then fails fast and the browser retries.
        ResponseEntity<String> finalizeDuringOutage = duringOutage(() -> {
            assertThat(put(issued, FILE)).isEqualTo(200);
            return finalizeWith(student, issued.submissionId(), issued.receipt());
        });
        assertThat(finalizeDuringOutage.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(code(finalizeDuringOutage)).isEqualTo(ErrorCodes.DATABASE_UNAVAILABLE);

        // Recovery comes after the deadline; the stamp signed at 23:59:50 still decides.
        clock.set(DEADLINE.plus(Duration.ofMinutes(5)));
        ResponseEntity<String> fin = finalizeWith(student, issued.submissionId(), issued.receipt());
        assertThat(fin.getStatusCode()).as(fin.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(fin.getBody());
        assertThat(body.get("status").asString()).isEqualTo("UPLOADED");
        assertThat(body.get("timing").asString()).isEqualTo("ON_TIME");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status::text AS status, timing::text AS timing, committed_at, receipt_nonce, idempotency_key, "
                        + "object_path FROM submissions WHERE id = ?", issued.submissionId());
        assertThat(row.get("status")).isEqualTo("UPLOADED");
        assertThat(row.get("timing")).isEqualTo("ON_TIME");
        assertThat(((java.sql.Timestamp) row.get("committed_at")).toInstant()).isEqualTo(arrival);
        assertThat(row.get("receipt_nonce")).isNotNull().isEqualTo(row.get("idempotency_key"));
        assertThat(row.get("object_path"))
                .isEqualTo("a/" + assignment.getId() + "/s/" + student.getId() + "/" + issued.submissionId());

        assertThat(jdbc.queryForList("SELECT coalesce(from_status::text, '-') || '>' || to_status::text || ':' || actor::text "
                + "FROM submission_events WHERE submission_id = ? ORDER BY at, id", String.class, issued.submissionId()))
                .containsExactly("->COMMITTED:RECEIPT", "COMMITTED>UPLOADED:BROWSER_FINALIZE");

        // The verify job was enqueued with the transition; the stored bytes hash to the committed sha256.
        drainService.drain(Duration.ofSeconds(30));
        assertThat(status(issued.submissionId())).isEqualTo("VERIFIED");
    }

    @Test
    void redeemingTheSameReceiptTwiceReplaysTheStatusAndCreatesOneRow() {
        CommitResponse issued = receiptWithBytes(student);

        assertThat(finalizeWith(student, issued.submissionId(), issued.receipt()).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Even after the grace window: a retry of an already-redeemed receipt learns its status.
        clock.set(DEADLINE.plusSeconds(GRACE_SECONDS + 60));
        ResponseEntity<String> again = finalizeWith(student, issued.submissionId(), issued.receipt());

        assertThat(again.getStatusCode()).as(again.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(again.getBody()).get("status").asString()).isEqualTo("UPLOADED");
        assertThat(count("SELECT count(*) FROM submissions")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(1);
    }

    @Test
    void anAlteredOrForeignReceiptIsRefusedWith403() {
        CommitResponse issued = receiptWithBytes(student);
        String token = issued.receipt();
        int dot = token.indexOf('.');

        // Payload re-signed by nobody: claim an earlier stamp by changing a payload byte.
        String payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        String earlier = payload.replace(Long.toString(issued.committedAt().toEpochMilli()),
                Long.toString(issued.committedAt().minusSeconds(3600).toEpochMilli()));
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(earlier.getBytes(StandardCharsets.UTF_8))
                + token.substring(dot);
        String garbage = "not-a-receipt";

        for (String bad : List.of(forged, garbage)) {
            ResponseEntity<String> r = finalizeWith(student, issued.submissionId(), bad);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(code(r)).isEqualTo(ErrorCodes.RECEIPT_INVALID);
        }

        // A valid receipt presented by another student, or for another submission id.
        ResponseEntity<String> otherStudentTry = finalizeWith(otherStudent, issued.submissionId(), token);
        assertThat(otherStudentTry.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(otherStudentTry)).isEqualTo(ErrorCodes.RECEIPT_INVALID);
        ResponseEntity<String> otherIdTry = finalizeWith(student, UUID.randomUUID(), token);
        assertThat(otherIdTry.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(otherIdTry)).isEqualTo(ErrorCodes.RECEIPT_INVALID);

        assertThat(count("SELECT count(*) FROM submissions")).isZero();
    }

    @Test
    void aReceiptRedeemedAfterItsGraceWindowIsStale() {
        CommitResponse issued = receiptWithBytes(student);

        clock.set(DEADLINE.plusSeconds(GRACE_SECONDS).plusSeconds(1));
        ResponseEntity<String> r = finalizeWith(student, issued.submissionId(), issued.receipt());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(code(r)).isEqualTo(ErrorCodes.RECEIPT_STALE);
        assertThat(count("SELECT count(*) FROM submissions")).isZero();
    }

    @Test
    void aLateReceiptGetsItsOwnGraceWindowFromItsStampAndIsMarkedLate() {
        // Committed 10 minutes after the deadline: redeemable until stamp + grace, not deadline + grace.
        clock.set(DEADLINE.plus(Duration.ofMinutes(10)));
        CommitResponse issued = receiptWithBytes(student);

        clock.set(DEADLINE.plusSeconds(GRACE_SECONDS).plus(Duration.ofMinutes(5)));
        ResponseEntity<String> r = finalizeWith(student, issued.submissionId(), issued.receipt());

        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(r.getBody()).get("timing").asString()).isEqualTo("LATE");
    }

    @Test
    void finalizeBeforeTheBytesArriveIsRetryableAndSucceedsOnceTheyDo() {
        CommitResponse issued = duringOutage(() -> read(commitRaw(student), CommitResponse.class));
        assertThat(issued.status()).isEqualTo("RECEIPT_ISSUED");

        ResponseEntity<String> early = finalizeWith(student, issued.submissionId(), issued.receipt());
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(early)).isEqualTo(ErrorCodes.BYTES_NOT_FOUND);
        assertThat(count("SELECT count(*) FROM submissions")).isZero();

        assertThat(put(issued, FILE)).isEqualTo(200);
        ResponseEntity<String> later = finalizeWith(student, issued.submissionId(), issued.receipt());
        assertThat(later.getStatusCode()).as(later.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aStudentNoLongerEnrolledIsRefusedAndTheirObjectDeleted() {
        CommitResponse issued = receiptWithBytes(student);
        String objectPath = "a/" + assignment.getId() + "/s/" + student.getId() + "/" + issued.submissionId();
        assertThat(storage.stat(objectPath)).isPresent();

        enrollments.deleteById(new Enrollment.Id(course.getId(), student.getId()));
        ResponseEntity<String> r = finalizeWith(student, issued.submissionId(), issued.receipt());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(r)).isEqualTo(ErrorCodes.NOT_ENROLLED);
        assertThat(storage.stat(objectPath)).isEmpty();
        assertThat(count("SELECT count(*) FROM submissions")).isZero();
    }

    @Test
    void assignmentLimitsSkippedAtCommitAreEnforcedAtFinalize() {
        // The commit could not read the assignment, so a disallowed type still got a receipt.
        CommitResponse issued = duringOutage(() -> {
            CommitResponse c = read(commitRaw(student, "image/png"), CommitResponse.class);
            assertThat(put(c, FILE)).isEqualTo(200);
            return c;
        });
        assertThat(issued.status()).isEqualTo("RECEIPT_ISSUED");

        ResponseEntity<String> r = finalizeWith(student, issued.submissionId(), issued.receipt());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(code(r)).isEqualTo(ErrorCodes.TYPE_NOT_ALLOWED);
        assertThat(storage.stat("a/" + assignment.getId() + "/s/" + student.getId() + "/" + issued.submissionId())).isEmpty();
        assertThat(count("SELECT count(*) FROM submissions")).isZero();
    }

    @Test
    void redeemingAReceiptSupersedesTheStudentsEarlierLiveSubmission() {
        CommitResponse before = read(commitRaw(student), CommitResponse.class);
        assertThat(before.status()).isEqualTo("COMMITTED");

        CommitResponse issued = receiptWithBytes(student);
        assertThat(finalizeWith(student, issued.submissionId(), issued.receipt()).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(status(before.submissionId())).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT superseded_by FROM submissions WHERE id = ?", UUID.class,
                before.submissionId())).isEqualTo(issued.submissionId());
        assertThat(count("SELECT count(*) FROM submissions WHERE status NOT IN ('EXPIRED','SUPERSEDED')")).isEqualTo(1);
    }

    @Test
    void withTheDatabaseUpTheNormalPathIsUnchanged() {
        ResponseEntity<String> commit = commitRaw(student);
        assertThat(commit.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        CommitResponse c = read(commit, CommitResponse.class);
        assertThat(c.status()).isEqualTo("COMMITTED");
        assertThat(c.timing()).isEqualTo("ON_TIME");
        assertThat(c.receipt()).isNull();

        assertThat(put(c, FILE)).isEqualTo(200);
        // What curl --data "" sends (deploy/smoke-flow.sh): a form Content-Type and an empty body.
        HttpHeaders formHeaders = auth(student);
        formHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> fin = rest.postForEntity("/api/submissions/" + c.submissionId() + "/finalize",
                new HttpEntity<>("", formHeaders), String.class);
        assertThat(fin.getStatusCode()).as(fin.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(fin.getBody()).get("status").asString()).isEqualTo("UPLOADED");
        assertThat(jdbc.queryForObject("SELECT receipt_nonce FROM submissions WHERE id = ?", UUID.class,
                c.submissionId())).isNull();
    }

    // --- RECEIPT_ENABLED=false keeps the 503 ---------------------------------------------------

    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureTestRestTemplate
    @ActiveProfiles("test")
    @Import(ReceiptIT.TestClockConfig.class)
    @TestPropertySource(properties = "dd.receipt.enabled=false")
    class ReceiptsDisabled {

        @DynamicPropertySource
        static void disabledProperties(DynamicPropertyRegistry registry) {
            properties(registry);
        }

        @Test
        void commitDuringOutageGets503WithRetryAfter(
                @Autowired TestRestTemplate disabledRest, @Autowired DataSource disabledPool) {
            HttpEntity<CommitRequest> request = commitRequest(student, "application/pdf");
            String path = "/api/assignments/" + assignment.getId() + "/commits";

            ResponseEntity<String> down = duringOutage(
                    () -> disabledRest.postForEntity(path, request, String.class), disabledPool);

            assertThat(down.getStatusCode()).as(down.getBody()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(down.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(code(down)).isEqualTo(ErrorCodes.DATABASE_UNAVAILABLE);
            assertThat(down.getBody()).doesNotContain("receipt");

            ResponseEntity<String> up = disabledRest.postForEntity(path, request, String.class);
            assertThat(up.getStatusCode()).as(up.getBody()).isEqualTo(HttpStatus.CREATED);
            assertThat(objectMapper.readTree(up.getBody()).get("status").asString()).isEqualTo("COMMITTED");
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * Runs {@code action} with the database route cut, then restores it. Hikari hands out a
     * connection used in the last 500 ms without checking it, so connections the outage killed
     * would fail the next request even though the database is back. A real outage outlasts that
     * window; evicting the pooled connections models "the database has been back for a while".
     */
    private <T> T duringOutage(Supplier<T> action, DataSource... otherPools) {
        try {
            DATABASE_PROXY.disable();
        } catch (IOException e) {
            throw new UncheckedIOException("could not cut the database route", e);
        }
        try {
            return action.get();
        } finally {
            try {
                DATABASE_PROXY.enable();
            } catch (IOException e) {
                throw new UncheckedIOException("could not restore the database route", e);
            }
            evictPooledConnections(dataSource);
            for (DataSource pool : otherPools) {
                evictPooledConnections(pool);
            }
        }
    }

    private static void evictPooledConnections(DataSource pool) {
        try {
            pool.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("the test DataSource is expected to be HikariCP", e);
        }
    }

    private CommitResponse receiptWithBytes(User as) {
        return duringOutage(() -> {
            ResponseEntity<String> r = commitRaw(as);
            assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.CREATED);
            CommitResponse c = read(r, CommitResponse.class);
            assertThat(c.status()).isEqualTo("RECEIPT_ISSUED");
            assertThat(put(c, FILE)).isEqualTo(200);
            return c;
        });
    }

    private ResponseEntity<String> commitRaw(User as) {
        return commitRaw(as, "application/pdf");
    }

    private ResponseEntity<String> commitRaw(User as, String contentType) {
        return rest.postForEntity("/api/assignments/" + assignment.getId() + "/commits",
                commitRequest(as, contentType), String.class);
    }

    private HttpEntity<CommitRequest> commitRequest(User as, String contentType) {
        HttpHeaders headers = auth(as);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return new HttpEntity<>(new CommitRequest(SHA, FILE.length, contentType), headers);
    }

    private ResponseEntity<String> finalizeWith(User as, UUID submissionId, String receipt) {
        return rest.postForEntity("/api/submissions/" + submissionId + "/finalize",
                new HttpEntity<>(new FinalizeRequest(receipt), auth(as)), String.class);
    }

    private int put(CommitResponse c, byte[] bytes) {
        try {
            URI signed = URI.create(c.uploadUrl());
            URI local = new URI("http", null, "localhost", port, signed.getPath(), signed.getQuery(), null);
            HttpRequest.Builder put = HttpRequest.newBuilder(local).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
            c.uploadHeaders().forEach(put::header);
            return http.send(put.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (IOException e) {
            throw new UncheckedIOException("PUT to the local storage server failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during the PUT", e);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("signed URL is not a valid URI: " + c.uploadUrl(), e);
        }
    }

    private HttpHeaders auth(User as) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.accessToken(jwtService, as));
        return headers;
    }

    private <T> T read(ResponseEntity<String> r, Class<T> type) {
        return objectMapper.readValue(r.getBody(), type);
    }

    private String code(ResponseEntity<String> r) {
        return objectMapper.readTree(r.getBody()).get("code").asString();
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status::text FROM submissions WHERE id = ?", String.class, id);
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK specification", e);
        }
    }
}
