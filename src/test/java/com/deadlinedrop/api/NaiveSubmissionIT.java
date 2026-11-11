package com.deadlinedrop.api;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.dto.BaselineDtos.BaselineSubmissionResponse;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Enrollment;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.Timing;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.BaselineSubmissionRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.security.JwtService;
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.support.MutableClock;
import com.deadlinedrop.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"test", "naive"})
@Import({TestcontainersConfiguration.class, NaiveSubmissionIT.TestClockConfig.class})
@TestPropertySource(properties = {
        "JWT_SECRET=dGVzdC1zZWNyZXQta2V5LXdpdGgtYXQtbGVhc3QtMzItYnl0ZXMtbG9uZw==",
        "server.tomcat.max-swallow-size=-1"
})
class NaiveSubmissionIT {

    private static final Path TEMP_ROOT;

    static {
        try {
            TEMP_ROOT = Files.createTempDirectory("dd-naive-it-");
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
        registry.add("dd.storage.root", () -> TEMP_ROOT.toAbsolutePath().toString());
    }

    private final TestRestTemplate restTemplate;
    private final MutableClock clock;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final AssignmentRepository assignmentRepository;
    private final BaselineSubmissionRepository baselineSubmissionRepository;
    private final StoragePort storagePort;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private User student1;
    private User student2;
    private Assignment assignment;
    private Instant deadline;

    @Autowired
    public NaiveSubmissionIT(
            TestRestTemplate restTemplate,
            MutableClock clock,
            UserRepository userRepository,
            CourseRepository courseRepository,
            EnrollmentRepository enrollmentRepository,
            AssignmentRepository assignmentRepository,
            BaselineSubmissionRepository baselineSubmissionRepository,
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
        this.baselineSubmissionRepository = baselineSubmissionRepository;
        this.storagePort = storagePort;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM baseline_submissions");
        enrollmentRepository.deleteAll();
        assignmentRepository.deleteAll();
        courseRepository.deleteAll();
        userRepository.deleteAll();

        deadline = Instant.parse("2026-11-20T23:59:59Z");
        clock.set(Instant.parse("2026-11-20T12:00:00Z"));

        User teacher = userRepository.save(User.create(
                "teacher@dd.local",
                passwordEncoder.encode("Password123!"),
                Role.TEACHER,
                "Prof. Smith"
        ));

        student1 = userRepository.save(User.create(
                "student1@dd.local",
                passwordEncoder.encode("Password123!"),
                Role.STUDENT,
                "Student One"
        ));

        student2 = userRepository.save(User.create(
                "student2@dd.local",
                passwordEncoder.encode("Password123!"),
                Role.STUDENT,
                "Student Two"
        ));

        Course course = courseRepository.save(Course.create("CS101", "Introduction to Systems", teacher.getId()));
        enrollmentRepository.save(Enrollment.enroll(course.getId(), student1.getId()));

        assignment = assignmentRepository.save(Assignment.create(
                course.getId(),
                "Lab 1",
                deadline,
                900,
                26214400L,
                List.of("application/pdf", "application/zip"),
                teacher.getId()
        ));
    }

    private HttpEntity<MultiValueMap<String, Object>> buildMultipartRequest(
            byte[] fileBytes,
            String filename,
            String contentType,
            String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) {
            headers.setBearerAuth(token);
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(fileResource, partHeaders);
        body.add("file", filePart);

        return new HttpEntity<>(body, headers);
    }

    @Test
    void oneMbPdfBeforeDeadlineReturns201OnTimeAndWritesDatabaseAndStorage() throws Exception {
        byte[] payload = new byte[1024 * 1024]; // 1 MB
        payload[0] = '%';
        payload[1] = 'P';
        payload[2] = 'D';
        payload[3] = 'F';

        String token = TestTokens.accessToken(jwtService, student1);
        HttpEntity<MultiValueMap<String, Object>> request = buildMultipartRequest(
                payload,
                "sample.pdf",
                "application/pdf",
                token
        );

        String url = "/api/assignments/" + assignment.getId() + "/naive-submissions";
        ResponseEntity<BaselineSubmissionResponse> response = restTemplate.postForEntity(
                url,
                request,
                BaselineSubmissionResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BaselineSubmissionResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.submissionId()).isNotNull();
        assertThat(body.submittedAt()).isEqualTo(clock.instant());
        assertThat(body.stampSource()).isEqualTo("UPLOAD_COMPLETE");
        assertThat(body.timing()).isEqualTo(Timing.ON_TIME.name());

        // Verify baseline database record
        int count = baselineSubmissionRepository.countByAssignment(assignment.getId());
        assertThat(count).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, assignment_id, student_id, stamp_source, timing::text AS timing_text, size_bytes, object_path " +
                "FROM baseline_submissions WHERE id = ?",
                body.submissionId()
        );
        assertThat(row.get("assignment_id")).isEqualTo(assignment.getId());
        assertThat(row.get("student_id")).isEqualTo(student1.getId());
        assertThat(row.get("stamp_source")).isEqualTo("UPLOAD_COMPLETE");
        assertThat(row.get("timing_text")).isEqualTo("ON_TIME");
        assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(payload.length);

        String objectPath = (String) row.get("object_path");
        assertThat(objectPath).isEqualTo("a/" + assignment.getId() + "/s/" + student1.getId() + "/" + body.submissionId());

        // Verify object on disk via storagePort
        var stat = storagePort.stat(objectPath);
        assertThat(stat).isPresent();
        assertThat(stat.get().size()).isEqualTo(payload.length);
        assertThat(stat.get().contentType()).isEqualTo("application/pdf");

        try (InputStream in = storagePort.open(objectPath)) {
            byte[] readBytes = in.readAllBytes();
            assertThat(readBytes).isEqualTo(payload);
        }

        // Verify physical file on disk in temp dir
        Path expectedFilePath = TEMP_ROOT.resolve(objectPath);
        assertThat(Files.exists(expectedFilePath)).isTrue();
        assertThat(Files.size(expectedFilePath)).isEqualTo(payload.length);
    }

    @Test
    void clockMovedPastDeadlineReturnsLate() {
        // Advance clock 1 minute past deadline
        Instant pastDeadline = deadline.plusSeconds(60);
        clock.set(pastDeadline);

        byte[] payload = new byte[1024]; // 1 KB
        String token = TestTokens.accessToken(jwtService, student1);
        HttpEntity<MultiValueMap<String, Object>> request = buildMultipartRequest(
                payload,
                "sample.pdf",
                "application/pdf",
                token
        );

        String url = "/api/assignments/" + assignment.getId() + "/naive-submissions";
        ResponseEntity<BaselineSubmissionResponse> response = restTemplate.postForEntity(
                url,
                request,
                BaselineSubmissionResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BaselineSubmissionResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.submittedAt()).isEqualTo(pastDeadline);
        assertThat(body.stampSource()).isEqualTo("UPLOAD_COMPLETE");
        assertThat(body.timing()).isEqualTo(Timing.LATE.name());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT stamp_source, timing::text AS timing_text FROM baseline_submissions WHERE id = ?",
                body.submissionId()
        );
        assertThat(row.get("stamp_source")).isEqualTo("UPLOAD_COMPLETE");
        assertThat(row.get("timing_text")).isEqualTo("LATE");
    }

    @Test
    void twentySixMbFileReturns413FileTooLarge() {
        byte[] payload = new byte[26 * 1024 * 1024]; // 26 MB

        String token = TestTokens.accessToken(jwtService, student1);
        HttpEntity<MultiValueMap<String, Object>> request = buildMultipartRequest(
                payload,
                "large.pdf",
                "application/pdf",
                token
        );

        String url = "/api/assignments/" + assignment.getId() + "/naive-submissions";
        ResponseEntity<String> response = restTemplate.postForEntity(
                url,
                request,
                String.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(response.getBody()).contains("\"code\":\"FILE_TOO_LARGE\"");
    }

    @Test
    void textPlainReturns415TypeNotAllowed() {
        byte[] payload = "plain text essay".getBytes();

        String token = TestTokens.accessToken(jwtService, student1);
        HttpEntity<MultiValueMap<String, Object>> request = buildMultipartRequest(
                payload,
                "notes.txt",
                "text/plain",
                token
        );

        String url = "/api/assignments/" + assignment.getId() + "/naive-submissions";
        ResponseEntity<String> response = restTemplate.postForEntity(
                url,
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(response.getBody()).contains("\"code\":\"TYPE_NOT_ALLOWED\"");
    }

    @Test
    void anotherCoursesStudentReturns403NotEnrolled() {
        byte[] payload = new byte[1024];

        // student2 is not enrolled in course CS101
        String token = TestTokens.accessToken(jwtService, student2);
        HttpEntity<MultiValueMap<String, Object>> request = buildMultipartRequest(
                payload,
                "sample.pdf",
                "application/pdf",
                token
        );

        String url = "/api/assignments/" + assignment.getId() + "/naive-submissions";
        ResponseEntity<String> response = restTemplate.postForEntity(
                url,
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(response.getBody()).contains("\"code\":\"NOT_ENROLLED\"");
    }
}
