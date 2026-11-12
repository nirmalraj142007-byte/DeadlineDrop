package com.deadlinedrop.api;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.dto.DomainDtos.ExportRow;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.Timing;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.security.JwtService;
import com.deadlinedrop.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"test", "direct-finalize"})
@Import(TestcontainersConfiguration.class)
class DomainExportDirectFinalizeIT {

    private final TestRestTemplate restTemplate;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final AssignmentRepository assignmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JdbcTemplate jdbcTemplate;

    private User teacher;
    private User student;
    private Course course;
    private Assignment assignment;
    private String teacherToken;

    @Autowired
    public DomainExportDirectFinalizeIT(
            TestRestTemplate restTemplate,
            UserRepository userRepository,
            CourseRepository courseRepository,
            AssignmentRepository assignmentRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            JdbcTemplate jdbcTemplate) {
        this.restTemplate = restTemplate;
        this.userRepository = userRepository;
        this.courseRepository = courseRepository;
        this.assignmentRepository = assignmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE submission_events, jobs, submissions, baseline_submissions, enrollments, assignments, courses, users CASCADE");

        teacher = userRepository.save(User.create("teacher@dd.local", passwordEncoder.encode("Pass123!"), Role.TEACHER, "Teacher"));
        student = userRepository.save(User.create("student@dd.local", passwordEncoder.encode("Pass123!"), Role.STUDENT, "Student"));
        course = courseRepository.save(Course.create("CS101", "CS 101", teacher.getId()));
        assignment = assignmentRepository.save(Assignment.create(
                course.getId(), "Lab 1", Instant.now().plusSeconds(86400), 900, 10485760L, List.of("application/pdf"), teacher.getId()
        ));

        teacherToken = TestTokens.accessToken(jwtService, teacher);
    }

    @Test
    void exportUnderDirectFinalizeProfileReadsBaselineSubmissions() {
        UUID subId = UUID.randomUUID();
        Instant submittedAt = Instant.parse("2026-11-20T12:00:00Z");

        jdbcTemplate.update(
                "INSERT INTO baseline_submissions (id, assignment_id, student_id, idempotency_key, object_path, size_bytes, submitted_at, stamp_source, timing) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::submission_timing)",
                subId, assignment.getId(), student.getId(), UUID.randomUUID(), "path/to/obj", 4096L, Timestamp.from(submittedAt), "FINALIZE", Timing.LATE.name()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(teacherToken);

        // JSON export
        ResponseEntity<List<ExportRow>> jsonResp = restTemplate.exchange(
                "/api/assignments/" + assignment.getId() + "/export?format=json",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(jsonResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jsonResp.getBody()).hasSize(1);
        ExportRow row = jsonResp.getBody().get(0);
        assertThat(row.studentId()).isEqualTo(student.getId());
        assertThat(row.studentEmail()).isEqualTo("student@dd.local");
        assertThat(row.submissionId()).isEqualTo(subId);
        assertThat(row.stampedAt()).isEqualTo(submittedAt);
        assertThat(row.status()).isEqualTo("SUBMITTED");
        assertThat(row.stampSource()).isEqualTo("FINALIZE");
        assertThat(row.timing()).isEqualTo("LATE");

        // CSV export
        ResponseEntity<String> csvResp = restTemplate.exchange(
                "/api/assignments/" + assignment.getId() + "/export?format=csv",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(csvResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csvResp.getBody()).contains("studentId,studentEmail,submissionId,stampedAt,uploadedAt,status,timing,stampSource");
        assertThat(csvResp.getBody()).contains("FINALIZE");
        assertThat(csvResp.getBody()).contains("SUBMITTED");
    }
}
