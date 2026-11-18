package com.deadlinedrop.security;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.support.HttpCalls;
import com.deadlinedrop.support.HttpCalls.Reply;
import com.deadlinedrop.support.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hostile text in every place a caller can put it: login, course code, assignment title and path
 * ids. Each answer is a 400, or the value is stored and read back exactly as sent. Never a 500,
 * and the users table is still there at the end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InjectionIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final UserRepository users;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final AssignmentRepository assignments;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final JwtService jwtService;

    @LocalServerPort
    private int port;

    private HttpCalls http;
    private String teacherToken;
    private Course course;

    @Autowired
    InjectionIT(UserRepository users, CourseRepository courses, EnrollmentRepository enrollments,
                AssignmentRepository assignments, PasswordEncoder passwordEncoder, JdbcTemplate jdbc,
                JwtService jwtService) {
        this.users = users;
        this.courses = courses;
        this.enrollments = enrollments;
        this.assignments = assignments;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.jwtService = jwtService;
    }

    @BeforeAll
    void seed() {
        clean();
        http = new HttpCalls(port);
        User teacher = users.save(User.create("inject-teacher@dd.local", passwordEncoder.encode("Secret123!"),
                Role.TEACHER, "Teacher"));
        course = courses.save(Course.create("INJECT1", "Injection", teacher.getId()));
        teacherToken = TestTokens.accessToken(jwtService, teacher);
    }

    @AfterAll
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM submission_events");
        jdbc.update("DELETE FROM submissions");
        jdbc.update("DELETE FROM baseline_submissions");
        enrollments.deleteAll();
        assignments.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    static Stream<String> hostileText() {
        return Stream.of(
                "' OR 1=1 --",
                "\"; DROP TABLE users; --",
                "%00",
                "\u0000",
                "x'; SELECT pg_sleep(5); --",
                "x".repeat(5_000),
                "x".repeat(100_000));
    }

    private static String quoted(String value) {
        return JSON.writeValueAsString(value);
    }

    /** Never a 5xx; and when the call succeeded, the value is echoed back exactly as sent. */
    private static void assertRefusedOrStoredLiterally(Reply reply, String field, String value) {
        assertThat(reply.status()).as("status for %s (body %s)", abbreviate(value), abbreviate(reply.body()))
                .isLessThan(500);
        if (reply.status() / 100 == 2) {
            assertThat(reply.body()).contains("\"" + field + "\":" + quoted(value));
        } else {
            assertThat(reply.status()).isEqualTo(400);
        }
    }

    private static String abbreviate(String text) {
        return text.length() > 80 ? text.substring(0, 80) + "...(" + text.length() + " chars)" : text;
    }

    // --- login -----------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] login email")
    @MethodSource("hostileText")
    void loginEmailNeverCausesAServerError(String value) {
        Reply asEmail = http.call("POST", "/api/auth/login", null,
                "{\"email\":" + quoted(value) + ",\"password\":\"whatever\"}");
        Reply embedded = http.call("POST", "/api/auth/login", null,
                "{\"email\":" + quoted(value.length() > 100 ? value : "a" + value + "@dd.local")
                        + ",\"password\":\"whatever\"}");

        assertThat(asEmail.status()).isIn(400, 401);
        assertThat(embedded.status()).isIn(400, 401);
    }

    @ParameterizedTest(name = "[{index}] login password")
    @MethodSource("hostileText")
    void loginPasswordNeverCausesAServerError(String value) {
        Reply reply = http.call("POST", "/api/auth/login", null,
                "{\"email\":\"inject-teacher@dd.local\",\"password\":" + quoted(value) + "}");

        assertThat(reply.status()).isIn(400, 401);
    }

    // --- course code and assignment title --------------------------------------------------------

    @ParameterizedTest(name = "[{index}] course code")
    @MethodSource("hostileText")
    void courseCodeIsRefusedOrStoredLiterally(String value) {
        Reply reply = http.call("POST", "/api/courses", teacherToken,
                "{\"code\":" + quoted(value) + ",\"title\":\"t\"}");

        assertRefusedOrStoredLiterally(reply, "code", value);
        if (reply.status() / 100 == 2) {
            Reply listed = http.call("GET", "/api/courses", teacherToken, null);
            assertThat(listed.body()).contains(quoted(value));
        }
    }

    @ParameterizedTest(name = "[{index}] assignment title")
    @MethodSource("hostileText")
    void assignmentTitleIsRefusedOrStoredLiterally(String value) {
        String body = JSON.writeValueAsString(Map.of(
                "title", value,
                "deadlineAt", Instant.now().plus(Duration.ofDays(30)).toString(),
                "graceSeconds", 60,
                "maxBytes", 1000,
                "allowedTypes", List.of("application/pdf")));

        Reply reply = http.call("POST", "/api/courses/" + course.getId() + "/assignments", teacherToken, body);

        assertRefusedOrStoredLiterally(reply, "title", value);
    }

    // --- ids in the path -------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] path id")
    @MethodSource("pathIds")
    void aPathIdThatIsNotAUuidIsRefusedBeforeAnyQueryRuns(String segment) {
        for (String template : List.of(
                "/api/assignments/%s", "/api/assignments/%s/dashboard", "/api/assignments/%s/export",
                "/api/submissions/%s", "/api/submissions/%s/download", "/api/courses/%s/assignments")) {
            Reply reply = http.call("GET", String.format(template, segment), teacherToken, null);

            // 400 from UUID conversion; the servlet firewall refuses an encoded ';' or '%00' itself and
            // that rejection reaches the client as a 401 from the error dispatch. Never a 5xx.
            assertThat(reply.status()).as("GET " + template + " with " + abbreviate(segment)).isIn(400, 401);
        }
        assertThat(http.call("POST", "/api/submissions/" + segment + "/finalize", teacherToken, null).status())
                .isLessThan(500);
    }

    static Stream<String> pathIds() {
        Stream<String> encoded = Stream.of("' OR 1=1 --", "\"; DROP TABLE users; --", "x".repeat(5_000))
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"));
        return Stream.concat(encoded, Stream.of("%00", "..%2F..%2Fetc%2Fpasswd", "1%20OR%201=1"));
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void theTablesAreStillThere() {
        Integer teachers = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, "inject-teacher@dd.local");

        assertThat(teachers).isEqualTo(1);
    }
}
