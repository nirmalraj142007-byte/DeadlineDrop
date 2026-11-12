package com.deadlinedrop.web;

import com.deadlinedrop.api.ErrorCodes;
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
import com.deadlinedrop.support.MutableClock;
import com.deadlinedrop.support.TestTokens;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app fails fast when the database disappears. Postgres is real; the outage is a real network
 * fault: every connection goes through Toxiproxy, and disabling the proxy closes the open ones and
 * refuses new ones. A student's commit must get a 503 with {@code Retry-After: 1} inside the
 * 2 s pool budget (plus slack), not hang, and the same request must succeed once the database is back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import(DatabaseDownIT.TestClockConfig.class)
class DatabaseDownIT {

    private static final int PROXY_PORT = 8666;
    private static final long FAIL_FAST_LIMIT_MS = 2_500;

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
            STORAGE_ROOT = Files.createTempDirectory("dd-database-down-it-");
        } catch (IOException e) {
            throw new UncheckedIOException("could not set up the Toxiproxy route to Postgres", e);
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

    private final TestRestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final JwtService jwtService;
    private final UserRepository users;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final AssignmentRepository assignments;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    DatabaseDownIT(TestRestTemplate restTemplate, ObjectMapper objectMapper, JwtService jwtService,
                   UserRepository users, CourseRepository courses, EnrollmentRepository enrollments,
                   AssignmentRepository assignments, PasswordEncoder passwordEncoder) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.jwtService = jwtService;
        this.users = users;
        this.courses = courses;
        this.enrollments = enrollments;
        this.assignments = assignments;
        this.passwordEncoder = passwordEncoder;
    }

    @Test
    void commitFailsFastWith503WhileTheDatabaseIsUnreachableAndSucceedsOnRetryAfterItReturns() throws IOException {
        User teacher = users.save(User.create("teacher@dd.local", passwordEncoder.encode("Password123!"),
                Role.TEACHER, "Prof. Rao"));
        User student = users.save(User.create("student1@dd.local", passwordEncoder.encode("Password123!"),
                Role.STUDENT, "Student One"));
        Course course = courses.save(Course.create("CS101", "Introduction to Systems", teacher.getId()));
        enrollments.save(Enrollment.enroll(course.getId(), student.getId()));
        Assignment assignment = assignments.save(Assignment.create(course.getId(), "Lab 1",
                Instant.parse("2026-11-20T23:59:59Z"), 900, 1_048_576L, List.of("application/pdf"),
                teacher.getId()));

        String path = "/api/assignments/" + assignment.getId() + "/commits";

        // An instance at 11:59 is warm. Without this, the timing below would also include this JVM's
        // first HTTP request (client setup, servlet init, first JWT decode), not just the outage.
        ResponseEntity<String> warmUp = restTemplate.postForEntity(path,
                commitRequest(student, UUID.randomUUID()), String.class);
        assertThat(warmUp.getStatusCode()).as(warmUp.getBody()).isEqualTo(HttpStatus.CREATED);

        UUID idempotencyKey = UUID.randomUUID();
        HttpEntity<CommitRequest> request = commitRequest(student, idempotencyKey);

        // Two attempts during the outage, because a dead database surfaces in two ways. A connection
        // the pool used under 500 ms ago is handed out unchecked and dies mid-transaction; once the
        // pool is empty, the next request waits out the 2 s pool timeout (CannotCreateTransactionException).
        // Which one the first attempt meets depends on timing; the second always meets the pool timeout.
        DATABASE_PROXY.disable();
        try {
            for (int attempt = 1; attempt <= 2; attempt++) {
                long start = System.nanoTime();
                ResponseEntity<String> down = restTemplate.postForEntity(path, request, String.class);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;

                assertThat(down.getStatusCode()).as("attempt %d: %s", attempt, down.getBody())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(down.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
                JsonNode problem = objectMapper.readTree(down.getBody());
                assertThat(problem.get("code").asString()).isEqualTo(ErrorCodes.DATABASE_UNAVAILABLE);
                assertThat(problem.get("requestId").asString()).isNotBlank();
                assertThat(elapsedMs).as("attempt %d: time to the 503, in ms", attempt)
                        .isLessThan(FAIL_FAST_LIMIT_MS);
            }
        } finally {
            DATABASE_PROXY.enable();
        }

        ResponseEntity<String> up = restTemplate.postForEntity(path, request, String.class);
        assertThat(up.getStatusCode()).as(up.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(up.getBody()).get("status").asString()).isEqualTo("COMMITTED");
    }

    private HttpEntity<CommitRequest> commitRequest(User student, UUID idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.accessToken(jwtService, student));
        headers.set("Idempotency-Key", idempotencyKey.toString());
        return new HttpEntity<>(new CommitRequest("c".repeat(64), 1024, "application/pdf"), headers);
    }
}
