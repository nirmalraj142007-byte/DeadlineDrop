package com.deadlinedrop.race;

import com.deadlinedrop.TestcontainersConfiguration;
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
import com.deadlinedrop.support.MutableClock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared pieces of the race tests that go through the real HTTP endpoints: one student, one
 * course, one assignment, a JWT, and helpers for commit, PUT to the local storage server, and
 * finalize.
 */
final class HttpRaceSupport {

    static final String STORAGE_SERVER_ENABLED = "dd.storage.server.enabled=true";
    static final String STORAGE_ROOT = "dd.storage.root=${java.io.tmpdir}/dd-http-race-it";

    private HttpRaceSupport() {
    }

    /**
     * Same annotations on every HTTP race test, so the ones without extra beans share one Spring
     * context (and one Postgres container).
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    @Import({TestcontainersConfiguration.class, HttpRaceSupport.Config.class})
    @TestPropertySource(properties = {STORAGE_SERVER_ENABLED, STORAGE_ROOT})
    @interface HttpRaceTest {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.of(RaceTestSupport.NOW);
        }

        @Bean
        Fixture httpRaceFixture(MutableClock clock, UserRepository users, CourseRepository courses,
                EnrollmentRepository enrollments, AssignmentRepository assignments, JwtService jwtService,
                JdbcTemplate jdbc, ObjectMapper objectMapper, Environment environment) {
            return new Fixture(clock, users, courses, enrollments, assignments, jwtService, jdbc, objectMapper,
                    environment);
        }
    }

    static final class Fixture {

        private final MutableClock clock;
        private final UserRepository users;
        private final CourseRepository courses;
        private final EnrollmentRepository enrollments;
        private final AssignmentRepository assignments;
        private final JwtService jwtService;
        private final JdbcTemplate jdbc;
        private final ObjectMapper objectMapper;
        private final Environment environment;
        private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        private UUID studentId;
        private UUID assignmentId;
        private String token;

        Fixture(MutableClock clock, UserRepository users, CourseRepository courses, EnrollmentRepository enrollments,
                AssignmentRepository assignments, JwtService jwtService, JdbcTemplate jdbc, ObjectMapper objectMapper,
                Environment environment) {
            this.clock = clock;
            this.users = users;
            this.courses = courses;
            this.enrollments = enrollments;
            this.assignments = assignments;
            this.jwtService = jwtService;
            this.jdbc = jdbc;
            this.objectMapper = objectMapper;
            this.environment = environment;
        }

        MutableClock clock() {
            return clock;
        }

        JdbcTemplate jdbc() {
            return jdbc;
        }

        UUID studentId() {
            return studentId;
        }

        UUID assignmentId() {
            return assignmentId;
        }

        /** Fresh course data; the assignment's deadline is {@link RaceTestSupport#DEADLINE}. */
        void setUp(int graceSeconds) {
            clean();
            clock.set(RaceTestSupport.NOW);
            User teacher = users.save(User.create("http-race-teacher@dd.local", "unused-hash", Role.TEACHER, "Race Teacher"));
            User student = users.save(User.create("http-race-student@dd.local", "unused-hash", Role.STUDENT, "Race Student"));
            Course course = courses.save(Course.create("HRACE1", "HTTP race course", teacher.getId()));
            enrollments.save(Enrollment.enroll(course.getId(), student.getId()));
            Assignment assignment = assignments.save(Assignment.create(course.getId(), "HTTP race lab",
                    RaceTestSupport.DEADLINE, graceSeconds, 1_048_576L, List.of("application/pdf"), teacher.getId()));
            studentId = student.getId();
            assignmentId = assignment.getId();
            // Signed with wall-clock times: the resource server checks expiry against the system
            // clock, not the test clock, so the token must be valid now, not on the simulated day.
            Instant issued = Instant.now();
            token = jwtService.issue(student, issued, issued.plus(Duration.ofHours(1))).accessToken();
        }

        void clean() {
            cleanSubmissions();
            enrollments.deleteAll();
            assignments.deleteAll();
            courses.deleteAll();
            users.deleteAll();
        }

        void cleanSubmissions() {
            jdbc.update("DELETE FROM jobs");
            jdbc.update("DELETE FROM submission_events");
            jdbc.update("UPDATE submissions SET superseded_by = NULL");
            jdbc.update("DELETE FROM submissions");
        }

        HttpResponse<String> commit(UUID idempotencyKey, String sha256, long sizeBytes) throws IOException, InterruptedException {
            String body = objectMapper.writeValueAsString(new CommitRequest(sha256, sizeBytes, "application/pdf"));
            HttpRequest request = HttpRequest.newBuilder(url("/api/assignments/" + assignmentId + "/commits"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }

        CommitResponse commitResponse(HttpResponse<String> response) {
            return objectMapper.readValue(response.body(), CommitResponse.class);
        }

        /** PUTs {@code bytes} to the signed URL, rewritten to the in-process storage server. */
        int put(CommitResponse commit, byte[] bytes) throws IOException, InterruptedException, URISyntaxException {
            URI signed = URI.create(Objects.requireNonNull(commit.uploadUrl(), "commit returned no upload URL"));
            URI local = new URI("http", null, "localhost", port(), signed.getPath(), signed.getQuery(), null);
            HttpRequest.Builder put = HttpRequest.newBuilder(local).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
            commit.uploadHeaders().forEach(put::header);
            return http.send(put.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        }

        HttpResponse<String> finalizeSubmission(UUID submissionId) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(url("/api/submissions/" + submissionId + "/finalize"))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }

        String json(HttpResponse<String> response, String field) {
            var node = objectMapper.readTree(response.body()).get(field);
            return node == null || node.isNull() ? null : node.asText();
        }

        String status(UUID submissionId) {
            return jdbc.queryForObject("SELECT status::text FROM submissions WHERE id = ?", String.class, submissionId);
        }

        int count(String sql, Object... args) {
            Integer n = jdbc.queryForObject(sql, Integer.class, args);
            return n == null ? 0 : n;
        }

        private int port() {
            return environment.getRequiredProperty("local.server.port", Integer.class);
        }

        private URI url(String path) {
            return URI.create("http://localhost:" + port() + path);
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK specification", e);
        }
    }

    /** {@code size} bytes whose content (and so hash) depends on {@code seed}. */
    static byte[] file(int size, int seed) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) seed;
        bytes[1] = (byte) (seed >> 8);
        return bytes;
    }
}
