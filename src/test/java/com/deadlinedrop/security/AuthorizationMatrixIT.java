package com.deadlinedrop.security;

import com.deadlinedrop.TestcontainersConfiguration;
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
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.support.HttpCalls;
import com.deadlinedrop.support.HttpCalls.Reply;
import com.deadlinedrop.support.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every /api endpoint and method, called as each kind of caller, with the expected status written
 * down. Two courses, two teachers, two students: the resource under test always belongs to course A,
 * so "other student" and "other teacher" are the callers from course B who must be turned away.
 *
 * <p>Order matters in one way: the one call that is allowed to change a resource (finalize, commit,
 * PATCH) succeeds once, as its owner. Every other caller is refused before the state is looked at.
 * The completeness test fails when a new /api route appears without a row here. The routes that
 * exist only under the naive and direct-finalize profiles are not registered in this profile, and
 * /internal and /admin/chaos have their own tests (JobsIT, ChaosEndpointIT).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationMatrixIT {

    private static final String PASSWORD = "Secret123!";
    private static final String SHA = "1".repeat(64);
    private static final byte[] PAYLOAD = "matrix-payload".getBytes(StandardCharsets.UTF_8);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Number of rows in {@link #table()}; the completeness test checks the table against the real routes. */
    private static final int ROWS = 22;

    enum Caller { ANONYMOUS, STUDENT, OTHER_STUDENT, TEACHER, OTHER_TEACHER, ADMIN }

    /** One row of the table: the route, how to fill its id and body, and what each caller should get. */
    record Endpoint(String method, String template, Supplier<UUID> id, Supplier<String> body,
                    Map<String, String> headers, Map<Caller, String> expected) {

        String path() {
            return id == null ? template : template.replaceAll("\\{[^}]+}", id.get().toString());
        }

        String key() {
            return method + " " + template.replaceAll("\\{[^}]+}", "{}");
        }
    }

    private final UserRepository users;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final AssignmentRepository assignments;
    private final SubmissionRepository submissions;
    private final TransitionRepository transitions;
    private final StoragePort storage;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final RequestMappingHandlerMapping mappings;
    private final JwtService jwtService;

    @LocalServerPort
    private int port;

    private final Map<Caller, User> people = new EnumMap<>(Caller.class);
    private Course courseA;
    private Course courseB;
    private Assignment readAssignment;
    private Assignment commitAssignment;
    private Assignment patchAssignment;
    private Assignment finalizeAssignment;
    private Assignment uploadAssignment;
    private Assignment downloadAssignment;
    private Row readSubmission;
    private Row finalizeSubmission;
    private Row uploadSubmission;
    private Row downloadSubmission;
    private HttpCalls http;
    private List<Endpoint> rows;

    @Autowired
    AuthorizationMatrixIT(UserRepository users, CourseRepository courses, EnrollmentRepository enrollments,
                          AssignmentRepository assignments, SubmissionRepository submissions,
                          TransitionRepository transitions, StoragePort storage, PasswordEncoder passwordEncoder,
                          JdbcTemplate jdbc,
                          @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings,
                          JwtService jwtService) {
        this.users = users;
        this.courses = courses;
        this.enrollments = enrollments;
        this.assignments = assignments;
        this.submissions = submissions;
        this.transitions = transitions;
        this.storage = storage;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.mappings = mappings;
        this.jwtService = jwtService;
    }

    @BeforeAll
    void seed() {
        clean();
        http = new HttpCalls(port);
        String hash = passwordEncoder.encode(PASSWORD);
        people.put(Caller.STUDENT, users.save(User.create("alice@dd.local", hash, Role.STUDENT, "Alice")));
        people.put(Caller.OTHER_STUDENT, users.save(User.create("bob@dd.local", hash, Role.STUDENT, "Bob")));
        people.put(Caller.TEACHER, users.save(User.create("teacher-a@dd.local", hash, Role.TEACHER, "Teacher A")));
        people.put(Caller.OTHER_TEACHER, users.save(User.create("teacher-b@dd.local", hash, Role.TEACHER, "Teacher B")));
        people.put(Caller.ADMIN, users.save(User.create("admin@dd.local", hash, Role.ADMIN, "Admin")));

        courseA = courses.save(Course.create("MATRIXA", "Course A", people.get(Caller.TEACHER).getId()));
        courseB = courses.save(Course.create("MATRIXB", "Course B", people.get(Caller.OTHER_TEACHER).getId()));
        enrollments.save(Enrollment.enroll(courseA.getId(), people.get(Caller.STUDENT).getId()));
        enrollments.save(Enrollment.enroll(courseB.getId(), people.get(Caller.OTHER_STUDENT).getId()));

        readAssignment = newAssignment("read");
        commitAssignment = newAssignment("commit");
        patchAssignment = newAssignment("patch");
        finalizeAssignment = newAssignment("finalize");
        uploadAssignment = newAssignment("upload");
        // The bulk download is refused until the grace window has ended, so this assignment is long past.
        downloadAssignment = assignments.save(Assignment.create(courseA.getId(), "download",
                Instant.now().minus(Duration.ofDays(2)), 60, 10_000_000L, List.of("application/pdf"),
                people.get(Caller.TEACHER).getId()));

        readSubmission = committed(readAssignment);
        finalizeSubmission = committed(finalizeAssignment);
        storage.put(finalizeSubmission.objectPath(), "application/pdf",
                new ByteArrayInputStream(PAYLOAD), PAYLOAD.length);
        uploadSubmission = committed(uploadAssignment);
        downloadSubmission = committed(downloadAssignment);
        storage.put(downloadSubmission.objectPath(), "application/pdf",
                new ByteArrayInputStream(PAYLOAD), PAYLOAD.length);
        transitions.transition(downloadSubmission.id(), Status.COMMITTED, Status.UPLOADED, Instant.now());

        rows = table();
    }

    @AfterAll
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM submission_events");
        jdbc.update("DELETE FROM submissions");
        jdbc.update("DELETE FROM baseline_submissions");
        enrollments.deleteAll();
        assignments.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    private Assignment newAssignment(String title) {
        return assignments.save(Assignment.create(courseA.getId(), title, Instant.now().plus(Duration.ofDays(30)),
                900, 10_000_000L, List.of("application/pdf"), people.get(Caller.TEACHER).getId()));
    }

    private Row committed(Assignment assignment) {
        UUID id = UUID.randomUUID();
        UUID studentId = people.get(Caller.STUDENT).getId();
        String path = "a/" + assignment.getId() + "/s/" + studentId + "/" + id;
        Instant now = Instant.now();
        Row row = new Row(id, assignment.getId(), studentId, UUID.randomUUID(), "f".repeat(64), SHA,
                PAYLOAD.length, "application/pdf", path, Status.COMMITTED, Timing.ON_TIME, now, now,
                null, null, null, null, null, now, now);
        submissions.insertCommitted(row);
        return row;
    }

    // --- the table -------------------------------------------------------------------------------

    private static Map<Caller, String> expect(String anonymous, String student, String otherStudent,
                                              String teacher, String otherTeacher, String admin) {
        Map<Caller, String> m = new EnumMap<>(Caller.class);
        m.put(Caller.ANONYMOUS, anonymous);
        m.put(Caller.STUDENT, student);
        m.put(Caller.OTHER_STUDENT, otherStudent);
        m.put(Caller.TEACHER, teacher);
        m.put(Caller.OTHER_TEACHER, otherTeacher);
        m.put(Caller.ADMIN, admin);
        return m;
    }

    private static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    private List<Endpoint> table() {
        String futureDeadline = Instant.now().plus(Duration.ofDays(30)).toString();
        Map<String, String> none = Map.of();
        String pass = "2xx";
        String unauth = "401";
        String deny = "403";
        return List.of(
                new Endpoint("POST", "/api/auth/login", null,
                        () -> json(Map.of("email", "teacher-a@dd.local", "password", PASSWORD)), none,
                        expect(pass, pass, pass, pass, pass, pass)),
                new Endpoint("GET", "/api/time", null, null, none, expect(pass, pass, pass, pass, pass, pass)),
                new Endpoint("GET", "/api/config", null, null, none, expect(pass, pass, pass, pass, pass, pass)),
                new Endpoint("GET", "/api/me", null, null, none, expect(unauth, pass, pass, pass, pass, pass)),
                new Endpoint("POST", "/api/courses", null,
                        () -> json(Map.of("code", "C" + UUID.randomUUID().toString().substring(0, 8), "title", "New")),
                        none, expect(unauth, deny, deny, pass, pass, deny)),
                new Endpoint("GET", "/api/courses", null, null, none, expect(unauth, pass, pass, pass, pass, deny)),
                new Endpoint("POST", "/api/courses/{id}/enrollments", () -> courseA.getId(),
                        () -> json(Map.of("emails", List.of("alice@dd.local"))), none,
                        expect(unauth, deny, deny, pass, deny, deny)),
                new Endpoint("POST", "/api/courses/{id}/assignments", () -> courseA.getId(),
                        () -> json(Map.of("title", "Made by matrix", "deadlineAt", futureDeadline,
                                "graceSeconds", 60, "maxBytes", 1000000, "allowedTypes", List.of("application/pdf"))),
                        none, expect(unauth, deny, deny, pass, deny, deny)),
                new Endpoint("GET", "/api/courses/{id}/assignments", () -> courseA.getId(), null, none,
                        expect(unauth, deny, deny, pass, deny, deny)),
                // The service would let an admin read it; the controller's @PreAuthorize (TEACHER, STUDENT) stops them first.
                new Endpoint("GET", "/api/assignments/{id}", () -> readAssignment.getId(), null, none,
                        expect(unauth, pass, deny, pass, deny, deny)),
                new Endpoint("PATCH", "/api/assignments/{id}", () -> patchAssignment.getId(),
                        () -> json(Map.of("title", "Renamed", "deadlineAt", futureDeadline, "graceSeconds", 60,
                                "maxBytes", 1000000, "allowedTypes", List.of("application/pdf"),
                                "version", patchAssignment.getVersion())),
                        none, expect(unauth, deny, deny, pass, deny, deny)),
                new Endpoint("GET", "/api/me/assignments", null, null, none,
                        expect(unauth, pass, pass, deny, deny, deny)),
                new Endpoint("POST", "/api/assignments/{id}/commits", () -> commitAssignment.getId(),
                        () -> json(Map.of("sha256", "a".repeat(64), "sizeBytes", 1234, "contentType", "application/pdf")),
                        Map.of("Idempotency-Key", UUID.randomUUID().toString()),
                        expect(unauth, pass, deny, deny, deny, deny)),
                new Endpoint("POST", "/api/submissions/{id}/upload-url", () -> uploadSubmission.id(),
                        () -> json(Map.of("sha256", SHA)), none, expect(unauth, pass, deny, deny, deny, deny)),
                new Endpoint("POST", "/api/submissions/{id}/finalize", () -> finalizeSubmission.id(), null, none,
                        expect(unauth, pass, deny, deny, deny, deny)),
                new Endpoint("GET", "/api/submissions/{id}", () -> readSubmission.id(), null, none,
                        expect(unauth, pass, deny, pass, deny, pass)),
                new Endpoint("GET", "/api/submissions/{id}/download", () -> downloadSubmission.id(), null, none,
                        expect(unauth, deny, deny, pass, deny, deny)),
                new Endpoint("GET", "/api/assignments/{id}/download-all", () -> downloadAssignment.getId(), null, none,
                        expect(unauth, deny, deny, pass, deny, deny)),
                new Endpoint("GET", "/api/assignments/{id}/dashboard", () -> readAssignment.getId(), null, none,
                        expect(unauth, deny, deny, pass, deny, deny)),
                new Endpoint("GET", "/api/assignments/{id}/export", () -> readAssignment.getId(), null, none,
                        expect(unauth, deny, deny, pass, deny, pass)),
                new Endpoint("POST", "/api/telemetry", null,
                        () -> json(Map.of("assignmentId", readAssignment.getId().toString(), "consentVersion", "v1")),
                        none, expect(unauth, pass, pass, deny, deny, deny)),
                new Endpoint("POST", "/api/survey", null,
                        () -> json(Map.of("assignmentId", readAssignment.getId().toString(), "markedLateBefore", "NO")),
                        none, expect(unauth, pass, pass, deny, deny, deny)));
    }

    /** Row indexes times callers; the rows themselves are read inside the test, after {@link #seed()}. */
    static Stream<Arguments> cases() {
        return IntStream.range(0, ROWS).boxed()
                .flatMap(i -> Stream.of(Caller.values()).map(c -> Arguments.of(i, c)));
    }

    @ParameterizedTest(name = "[{index}] row {0} as {1}")
    @MethodSource("cases")
    void everyCallerGetsTheExpectedStatus(int row, Caller caller) {
        Endpoint endpoint = rows.get(row);
        String bearer = caller == Caller.ANONYMOUS ? null : TestTokens.accessToken(jwtService, people.get(caller));

        Reply reply = http.call(endpoint.method(), endpoint.path(), bearer, endpoint.headers(),
                endpoint.body() == null ? null : endpoint.body().get());

        String expected = endpoint.expected().get(caller);
        String what = endpoint.method() + " " + endpoint.path() + " as " + caller + " -> " + reply.status()
                + " " + abbreviate(reply.body());
        if (expected.equals("2xx")) {
            assertThat(reply.status() / 100).as(what).isEqualTo(2);
        } else {
            assertThat(reply.status()).as(what).isEqualTo(Integer.parseInt(expected));
        }
    }

    private static String abbreviate(String body) {
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    @Test
    void theTableCoversEveryApiRouteTheApplicationRegisters() {
        Set<String> registered = new LinkedHashSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPathPatternsCondition() == null
                    ? Set.of() : info.getPathPatternsCondition().getPatternValues();
            for (String pattern : patterns) {
                if (pattern.startsWith("/api/")) {
                    info.getMethodsCondition().getMethods().forEach(m ->
                            registered.add(m.name() + " " + pattern.replaceAll("\\{[^}]+}", "{}")));
                }
            }
        }
        Set<String> covered = new LinkedHashSet<>();
        rows.forEach(e -> covered.add(e.key()));

        assertThat(rows).hasSize(ROWS);
        assertThat(covered).as("routes registered under /api with no row in the matrix")
                .containsAll(registered);
    }

    @Test
    void aTokenThatIsNotOursIsRejected() {
        for (String bogus : List.of("not.a.jwt", "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4Iiwicm9sZSI6IkFETUlOIn0.")) {
            assertThat(http.call("GET", "/api/me", bogus, null).status()).as(bogus).isEqualTo(401);
        }
    }

    @Test
    void anUnknownApiPathIsNeverAnonymouslyReadable() {
        assertThat(http.call("GET", "/api/no-such-endpoint", null, null).status()).isEqualTo(401);
        String student = TestTokens.accessToken(jwtService, people.get(Caller.STUDENT));
        assertThat(http.call("GET", "/api/no-such-endpoint", student, null).status()).isEqualTo(404);
    }
}
