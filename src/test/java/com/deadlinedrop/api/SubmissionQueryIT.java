package com.deadlinedrop.api;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.dto.ReadDtos.EventDto;
import com.deadlinedrop.api.dto.ReadDtos.ReceiptDto;
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
@Import({TestcontainersConfiguration.class, SubmissionQueryIT.TestClockConfig.class})
@TestPropertySource(properties = {
        "JWT_SECRET=dGVzdC1zZWNyZXQta2V5LXdpdGgtYXQtbGVhc3QtMzItYnl0ZXMtbG9uZw=="
})
class SubmissionQueryIT {

    private static final Path TEMP_ROOT;

    static {
        try {
            TEMP_ROOT = Files.createTempDirectory("dd-subquery-it-");
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
    private User student;
    private User otherStudent;
    private Course course;
    private Assignment assignment;

    @Autowired
    SubmissionQueryIT(
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
        clock.set(Instant.parse("2026-11-20T12:00:00Z"));

        teacher = userRepository.save(User.create("teacher@dd.local", passwordEncoder.encode("Secret123!"), Role.TEACHER, "Teacher"));
        otherTeacher = userRepository.save(User.create("otherteacher@dd.local", passwordEncoder.encode("Secret123!"), Role.TEACHER, "Other Teacher"));
        student = userRepository.save(User.create("student@dd.local", passwordEncoder.encode("Secret123!"), Role.STUDENT, "Student"));
        otherStudent = userRepository.save(User.create("otherstudent@dd.local", passwordEncoder.encode("Secret123!"), Role.STUDENT, "Other Student"));

        course = courseRepository.save(Course.create("CS101", "Systems", teacher.getId()));
        enrollmentRepository.save(Enrollment.enroll(course.getId(), student.getId()));
        enrollmentRepository.save(Enrollment.enroll(course.getId(), otherStudent.getId()));

        assignment = assignmentRepository.save(Assignment.create(
                course.getId(), "Lab 1", clock.instant().plusSeconds(3600), 900, 10_000_000L,
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
    @DisplayName("Owning student gets receipt with all fields and ordered events")
    void owningStudentCanGetReceiptWithOrderedEvents() {
        UUID subId = UUID.randomUUID();
        Instant t0 = clock.instant();
        Instant t1 = t0.plusSeconds(30);
        Instant t2 = t0.plusSeconds(60);

        submissionRepository.insertCommitted(new Row(
                subId, assignment.getId(), student.getId(), UUID.randomUUID(),
                "f".repeat(64), "a".repeat(64), 2048, "application/pdf",
                "a/" + assignment.getId() + "/s/" + student.getId() + "/" + subId,
                Status.COMMITTED, Timing.ON_TIME, t0, t0,
                null, null, null, null, null, t0, t0));
        submissionRepository.insertEvent(subId, null, Status.COMMITTED, Actor.COMMIT, t0);

        transitionRepository.transition(subId, Status.COMMITTED, Status.UPLOADED, t1);
        submissionRepository.insertEvent(subId, Status.COMMITTED, Status.UPLOADED, Actor.BROWSER_FINALIZE, t1);

        transitionRepository.transition(subId, Status.UPLOADED, Status.VERIFIED, t2);
        submissionRepository.insertEvent(subId, Status.UPLOADED, Status.VERIFIED, Actor.VERIFIER, t2);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.accessToken(jwtService, student));

        ResponseEntity<ReceiptDto> response = rest.exchange(
                "/api/submissions/" + subId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                ReceiptDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReceiptDto receipt = response.getBody();
        assertThat(receipt).isNotNull();
        assertThat(receipt.submissionId()).isEqualTo(subId);
        assertThat(receipt.assignmentId()).isEqualTo(assignment.getId());
        assertThat(receipt.assignmentTitle()).isEqualTo("Lab 1");
        assertThat(receipt.sha256()).isEqualTo("a".repeat(64));
        assertThat(receipt.sizeBytes()).isEqualTo(2048L);
        assertThat(receipt.contentType()).isEqualTo("application/pdf");
        assertThat(receipt.timing()).isEqualTo("ON_TIME");
        assertThat(receipt.status()).isEqualTo("VERIFIED");
        assertThat(receipt.committedAt()).isEqualTo(t0);
        assertThat(receipt.uploadedAt()).isEqualTo(t1);
        assertThat(receipt.verifiedAt()).isEqualTo(t2);
        assertThat(receipt.graceEndsAt()).isEqualTo(assignment.graceEndsAt());

        // Events ordered by at
        List<EventDto> events = receipt.events();
        assertThat(events).hasSize(3);

        assertThat(events.get(0).from()).isNull();
        assertThat(events.get(0).to()).isEqualTo("COMMITTED");
        assertThat(events.get(0).actor()).isEqualTo("COMMIT");
        assertThat(events.get(0).at()).isEqualTo(t0);

        assertThat(events.get(1).from()).isEqualTo("COMMITTED");
        assertThat(events.get(1).to()).isEqualTo("UPLOADED");
        assertThat(events.get(1).actor()).isEqualTo("BROWSER_FINALIZE");
        assertThat(events.get(1).at()).isEqualTo(t1);

        assertThat(events.get(2).from()).isEqualTo("UPLOADED");
        assertThat(events.get(2).to()).isEqualTo("VERIFIED");
        assertThat(events.get(2).actor()).isEqualTo("VERIFIER");
        assertThat(events.get(2).at()).isEqualTo(t2);
    }

    @Test
    @DisplayName("Course teacher can also retrieve receipt")
    void courseTeacherCanGetReceipt() {
        UUID subId = UUID.randomUUID();
        submissionRepository.insertCommitted(new Row(
                subId, assignment.getId(), student.getId(), UUID.randomUUID(),
                "f".repeat(64), "b".repeat(64), 1024, "application/pdf",
                "a/" + assignment.getId() + "/s/" + student.getId() + "/" + subId,
                Status.COMMITTED, Timing.ON_TIME, clock.instant(), clock.instant(),
                null, null, null, null, null, clock.instant(), clock.instant()));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.accessToken(jwtService, teacher));

        ResponseEntity<ReceiptDto> response = rest.exchange(
                "/api/submissions/" + subId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                ReceiptDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().submissionId()).isEqualTo(subId);
    }

    @Test
    @DisplayName("Forbidden for non-owning student and non-course teacher")
    void forbiddenForUnauthorizedUsers() {
        UUID subId = UUID.randomUUID();
        submissionRepository.insertCommitted(new Row(
                subId, assignment.getId(), student.getId(), UUID.randomUUID(),
                "f".repeat(64), "c".repeat(64), 1024, "application/pdf",
                "a/" + assignment.getId() + "/s/" + student.getId() + "/" + subId,
                Status.COMMITTED, Timing.ON_TIME, clock.instant(), clock.instant(),
                null, null, null, null, null, clock.instant(), clock.instant()));

        // Other student -> 403
        HttpHeaders otherStudentHeaders = new HttpHeaders();
        otherStudentHeaders.setBearerAuth(TestTokens.accessToken(jwtService, otherStudent));
        ResponseEntity<String> resp1 = rest.exchange(
                "/api/submissions/" + subId,
                HttpMethod.GET,
                new HttpEntity<>(otherStudentHeaders),
                String.class);
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Other teacher -> 403
        HttpHeaders otherTeacherHeaders = new HttpHeaders();
        otherTeacherHeaders.setBearerAuth(TestTokens.accessToken(jwtService, otherTeacher));
        ResponseEntity<String> resp2 = rest.exchange(
                "/api/submissions/" + subId,
                HttpMethod.GET,
                new HttpEntity<>(otherTeacherHeaders),
                String.class);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("NotFound for missing submission, Unauthenticated for no credentials")
    void notFoundAndUnauthenticated() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.accessToken(jwtService, student));

        ResponseEntity<String> notFoundResp = rest.exchange(
                "/api/submissions/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(notFoundResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> unauthResp = rest.exchange(
                "/api/submissions/" + UUID.randomUUID(),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class);
        assertThat(unauthResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
