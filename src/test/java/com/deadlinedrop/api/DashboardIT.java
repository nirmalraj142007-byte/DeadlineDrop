package com.deadlinedrop.api;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.dto.ReadDtos.DashboardDto;
import com.deadlinedrop.api.dto.ReadDtos.DashboardRow;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Enrollment;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.Submission.Actor;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, DashboardIT.TestClockConfig.class})
@TestPropertySource(properties = {
        "JWT_SECRET=dGVzdC1zZWNyZXQta2V5LXdpdGgtYXQtbGVhc3QtMzItYnl0ZXMtbG9uZw=="
})
class DashboardIT {

    private static final Path TEMP_ROOT;

    static {
        try {
            TEMP_ROOT = Files.createTempDirectory("dd-dashboard-it-");
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
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    private User teacher;
    private User otherTeacher;
    private User student1;
    private User student2;
    private User student3;
    private User student4;
    private Course course;
    private Assignment assignment;
    private Instant deadline;

    @Autowired
    DashboardIT(
            TestRestTemplate rest,
            MutableClock clock,
            UserRepository userRepository,
            CourseRepository courseRepository,
            EnrollmentRepository enrollmentRepository,
            AssignmentRepository assignmentRepository,
            SubmissionRepository submissionRepository,
            TransitionRepository transitionRepository,
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
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void setUp() {
        cleanDatabase();
        deadline = Instant.parse("2026-11-20T23:59:59Z");
        clock.set(Instant.parse("2026-11-20T12:00:00Z"));

        teacher = userRepository.save(User.create("prof.smith@dd.local", passwordEncoder.encode("Secret123!"), Role.TEACHER, "Prof. Smith"));
        otherTeacher = userRepository.save(User.create("prof.jones@dd.local", passwordEncoder.encode("Secret123!"), Role.TEACHER, "Prof. Jones"));

        student1 = userRepository.save(User.create("alice@dd.local", passwordEncoder.encode("Secret123!"), Role.STUDENT, "Alice Adams"));
        student2 = userRepository.save(User.create("bob@dd.local", passwordEncoder.encode("Secret123!"), Role.STUDENT, "Bob Brown"));
        student3 = userRepository.save(User.create("carol@dd.local", passwordEncoder.encode("Secret123!"), Role.STUDENT, "Carol Clark"));
        student4 = userRepository.save(User.create("david@dd.local", passwordEncoder.encode("Secret123!"), Role.STUDENT, "David Davis"));

        course = courseRepository.save(Course.create("CS101", "Systems", teacher.getId()));
        enrollmentRepository.save(Enrollment.enroll(course.getId(), student1.getId()));
        enrollmentRepository.save(Enrollment.enroll(course.getId(), student2.getId()));
        enrollmentRepository.save(Enrollment.enroll(course.getId(), student3.getId()));
        enrollmentRepository.save(Enrollment.enroll(course.getId(), student4.getId()));

        assignment = assignmentRepository.save(Assignment.create(
                course.getId(), "Lab 1", deadline, 900, 10_000_000L,
                List.of("application/pdf"), teacher.getId()));
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

    @Test
    @DisplayName("Dashboard aggregates statuses, timings, failed jobs and returns ETag")
    void dashboardAggregatesAndReflectsAllStatusesAndTiming() {
        // student1: no submission -> NOT_SUBMITTED

        // student2: committed, uploaded, verified ON_TIME
        UUID sub2Id = UUID.randomUUID();
        submissionRepository.insertCommitted(new Row(
                sub2Id, assignment.getId(), student2.getId(), UUID.randomUUID(),
                "f".repeat(64), "2".repeat(64), 1024, "application/pdf",
                "a/" + assignment.getId() + "/s/" + student2.getId() + "/" + sub2Id,
                Status.COMMITTED, Timing.ON_TIME, clock.instant(), clock.instant(),
                null, null, null, null, null, clock.instant(), clock.instant()));
        transitionRepository.transition(sub2Id, Status.COMMITTED, Status.UPLOADED, clock.instant());
        transitionRepository.transition(sub2Id, Status.UPLOADED, Status.VERIFIED, clock.instant());

        // student3: committed ON_TIME, then expired
        UUID sub3Id = UUID.randomUUID();
        submissionRepository.insertCommitted(new Row(
                sub3Id, assignment.getId(), student3.getId(), UUID.randomUUID(),
                "f".repeat(64), "3".repeat(64), 2048, "application/pdf",
                "a/" + assignment.getId() + "/s/" + student3.getId() + "/" + sub3Id,
                Status.COMMITTED, Timing.ON_TIME, clock.instant(), clock.instant(),
                null, null, null, null, null, clock.instant(), clock.instant()));
        transitionRepository.transition(sub3Id, Status.COMMITTED, Status.EXPIRED, clock.instant());

        // student4: submitted LATE, uploaded
        UUID sub4Id = UUID.randomUUID();
        submissionRepository.insertCommitted(new Row(
                sub4Id, assignment.getId(), student4.getId(), UUID.randomUUID(),
                "f".repeat(64), "4".repeat(64), 4096, "application/pdf",
                "a/" + assignment.getId() + "/s/" + student4.getId() + "/" + sub4Id,
                Status.COMMITTED, Timing.LATE, clock.instant().plusSeconds(60), clock.instant().plusSeconds(60),
                null, null, null, null, null, clock.instant(), clock.instant()));
        transitionRepository.transition(sub4Id, Status.COMMITTED, Status.UPLOADED, clock.instant().plusSeconds(65));

        // Insert a FAILED job for sub2
        jdbcTemplate.update(
                "INSERT INTO jobs (type, submission_id, status, attempts, last_error) VALUES ('VERIFY', ?, 'FAILED', 3, 'Checksum error')",
                sub2Id);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.accessToken(jwtService, teacher));
        ResponseEntity<DashboardDto> response = rest.exchange(
                "/api/assignments/" + assignment.getId() + "/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                DashboardDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String etag = response.getHeaders().getETag();
        assertThat(etag).isNotNull().startsWith("\"").endsWith("\"");

        DashboardDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.assignmentId()).isEqualTo(assignment.getId());
        assertThat(dto.title()).isEqualTo("Lab 1");
        assertThat(dto.failedJobs()).isEqualTo(1L);

        // Status counts
        assertThat(dto.countsByStatus()).containsEntry("NOT_SUBMITTED", 1L);
        assertThat(dto.countsByStatus()).containsEntry("VERIFIED", 1L);
        assertThat(dto.countsByStatus()).containsEntry("EXPIRED", 1L);
        assertThat(dto.countsByStatus()).containsEntry("UPLOADED", 1L);

        // Timing counts
        assertThat(dto.countsByTiming()).containsEntry("ON_TIME", 2L);
        assertThat(dto.countsByTiming()).containsEntry("LATE", 1L);

        // Rows ordered by email ASC: alice, bob, carol, david
        assertThat(dto.rows()).hasSize(4);
        DashboardRow r1 = dto.rows().get(0);
        assertThat(r1.email()).isEqualTo("alice@dd.local");
        assertThat(r1.status()).isEqualTo("NOT_SUBMITTED");
        assertThat(r1.timing()).isNull();
        assertThat(r1.submissionId()).isNull();

        DashboardRow r2 = dto.rows().get(1);
        assertThat(r2.email()).isEqualTo("bob@dd.local");
        assertThat(r2.status()).isEqualTo("VERIFIED");
        assertThat(r2.timing()).isEqualTo("ON_TIME");
        assertThat(r2.submissionId()).isEqualTo(sub2Id);

        DashboardRow r3 = dto.rows().get(2);
        assertThat(r3.email()).isEqualTo("carol@dd.local");
        assertThat(r3.status()).isEqualTo("EXPIRED");
        assertThat(r3.timing()).isEqualTo("ON_TIME");
        assertThat(r3.submissionId()).isEqualTo(sub3Id);

        DashboardRow r4 = dto.rows().get(3);
        assertThat(r4.email()).isEqualTo("david@dd.local");
        assertThat(r4.status()).isEqualTo("UPLOADED");
        assertThat(r4.timing()).isEqualTo("LATE");
        assertThat(r4.submissionId()).isEqualTo(sub4Id);
    }

    @Test
    @DisplayName("Superseded submission is excluded in favor of the active live submission")
    void dashboardSupersededSubmissionIgnoredInFavorOfLive() {
        // student1 submits once
        UUID sub1A = UUID.randomUUID();
        submissionRepository.insertCommitted(new Row(
                sub1A, assignment.getId(), student1.getId(), UUID.randomUUID(),
                "f".repeat(64), "1".repeat(64), 1024, "application/pdf",
                "a/" + assignment.getId() + "/s/" + student1.getId() + "/" + sub1A,
                Status.COMMITTED, Timing.ON_TIME, clock.instant(), clock.instant(),
                null, null, null, null, null, clock.instant(), clock.instant()));

        // student1 submits again (supersedes sub1A)
        submissionRepository.supersedeLive(assignment.getId(), student1.getId(), clock.instant());
        UUID sub1B = UUID.randomUUID();
        submissionRepository.insertCommitted(new Row(
                sub1B, assignment.getId(), student1.getId(), UUID.randomUUID(),
                "f".repeat(64), "5".repeat(64), 2048, "application/pdf",
                "a/" + assignment.getId() + "/s/" + student1.getId() + "/" + sub1B,
                Status.COMMITTED, Timing.ON_TIME, clock.instant().plusSeconds(10), clock.instant().plusSeconds(10),
                null, null, null, null, null, clock.instant(), clock.instant()));
        transitionRepository.transition(sub1B, Status.COMMITTED, Status.UPLOADED, clock.instant().plusSeconds(12));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.accessToken(jwtService, teacher));
        ResponseEntity<DashboardDto> response = rest.exchange(
                "/api/assignments/" + assignment.getId() + "/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                DashboardDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DashboardDto dto = response.getBody();
        assertThat(dto).isNotNull();

        DashboardRow aliceRow = dto.rows().stream()
                .filter(r -> r.email().equals("alice@dd.local"))
                .findFirst().orElseThrow();
        assertThat(aliceRow.submissionId()).isEqualTo(sub1B);
        assertThat(aliceRow.status()).isEqualTo("UPLOADED");
    }

    @Test
    @DisplayName("ETag header caching: second call with If-None-Match returns 304, updates when data changes")
    void etagAndIfNoneMatchReturns304() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.accessToken(jwtService, teacher));

        // First call -> 200 OK
        ResponseEntity<DashboardDto> resp1 = rest.exchange(
                "/api/assignments/" + assignment.getId() + "/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                DashboardDto.class);

        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.OK);
        String etag = resp1.getHeaders().getETag();
        assertThat(etag).isNotNull();

        // Second call with If-None-Match -> 304 Not Modified
        HttpHeaders headersWithEtag = new HttpHeaders();
        headersWithEtag.setBearerAuth(TestTokens.accessToken(jwtService, teacher));
        headersWithEtag.setIfNoneMatch(etag);

        ResponseEntity<String> resp2 = rest.exchange(
                "/api/assignments/" + assignment.getId() + "/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headersWithEtag),
                String.class);

        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        // State changes: student1 submits
        UUID subId = UUID.randomUUID();
        submissionRepository.insertCommitted(new Row(
                subId, assignment.getId(), student1.getId(), UUID.randomUUID(),
                "f".repeat(64), "e".repeat(64), 1024, "application/pdf",
                "a/" + assignment.getId() + "/s/" + student1.getId() + "/" + subId,
                Status.COMMITTED, Timing.ON_TIME, clock.instant(), clock.instant(),
                null, null, null, null, null, clock.instant(), clock.instant()));

        // Call again with old ETag -> 200 OK because data changed
        ResponseEntity<DashboardDto> resp3 = rest.exchange(
                "/api/assignments/" + assignment.getId() + "/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headersWithEtag),
                DashboardDto.class);

        assertThat(resp3.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp3.getHeaders().getETag()).isNotEqualTo(etag);
        assertThat(resp3.getBody().countsByStatus()).containsEntry("COMMITTED", 1L);
    }

    @Test
    @DisplayName("Authorization: non-owner teacher gets 403, student gets 403, unauthenticated gets 401")
    void authorizationEnforced() {
        // Non-owner teacher -> 403
        HttpHeaders otherTeacherHeaders = new HttpHeaders();
        otherTeacherHeaders.setBearerAuth(TestTokens.accessToken(jwtService, otherTeacher));
        ResponseEntity<String> respOtherTeacher = rest.exchange(
                "/api/assignments/" + assignment.getId() + "/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(otherTeacherHeaders),
                String.class);
        assertThat(respOtherTeacher.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Student -> 403
        HttpHeaders studentHeaders = new HttpHeaders();
        studentHeaders.setBearerAuth(TestTokens.accessToken(jwtService, student1));
        ResponseEntity<String> respStudent = rest.exchange(
                "/api/assignments/" + assignment.getId() + "/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(studentHeaders),
                String.class);
        assertThat(respStudent.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Unauthenticated -> 401
        ResponseEntity<String> respUnauth = rest.exchange(
                "/api/assignments/" + assignment.getId() + "/dashboard",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class);
        assertThat(respUnauth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Non-existent assignment -> 404
        HttpHeaders teacherHeaders = new HttpHeaders();
        teacherHeaders.setBearerAuth(TestTokens.accessToken(jwtService, teacher));
        ResponseEntity<String> respNotFound = rest.exchange(
                "/api/assignments/" + UUID.randomUUID() + "/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(teacherHeaders),
                String.class);
        assertThat(respNotFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
