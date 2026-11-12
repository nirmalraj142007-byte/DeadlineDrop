package com.deadlinedrop.api;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.dto.DomainDtos.AssignmentDto;
import com.deadlinedrop.api.dto.DomainDtos.CourseDto;
import com.deadlinedrop.api.dto.DomainDtos.CreateAssignmentRequest;
import com.deadlinedrop.api.dto.DomainDtos.CreateCourseRequest;
import com.deadlinedrop.api.dto.DomainDtos.EnrollRequest;
import com.deadlinedrop.api.dto.DomainDtos.EnrollResult;
import com.deadlinedrop.api.dto.DomainDtos.ExportRow;
import com.deadlinedrop.api.dto.DomainDtos.StudentAssignmentDto;
import com.deadlinedrop.api.dto.DomainDtos.TimeResponse;
import com.deadlinedrop.api.dto.DomainDtos.UpdateAssignmentRequest;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.domain.Timing;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.security.JwtService;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, DomainApiIT.TestClockConfig.class})
class DomainApiIT {

    private static final Instant SIMULATED_TIME = Instant.parse("2026-11-20T12:00:00Z");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.of(SIMULATED_TIME);
        }
    }

    private final TestRestTemplate restTemplate;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JdbcTemplate jdbcTemplate;
    private final MutableClock clock;
    private final ObjectMapper objectMapper;

    private User teacher1;
    private User teacher2;
    private User student1;
    private User student2;
    private User admin;

    private String teacher1Token;
    private String teacher2Token;
    private String student1Token;
    private String student2Token;
    private String adminToken;

    @Autowired
    public DomainApiIT(
            TestRestTemplate restTemplate,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            JdbcTemplate jdbcTemplate,
            MutableClock clock,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void setUp() {
        clock.set(SIMULATED_TIME);
        jdbcTemplate.execute("TRUNCATE TABLE submission_events, jobs, submissions, baseline_submissions, enrollments, assignments, courses, users CASCADE");

        teacher1 = userRepository.save(User.create("teacher1@dd.local", passwordEncoder.encode("Pass123!"), Role.TEACHER, "Teacher One"));
        teacher2 = userRepository.save(User.create("teacher2@dd.local", passwordEncoder.encode("Pass123!"), Role.TEACHER, "Teacher Two"));
        student1 = userRepository.save(User.create("student1@dd.local", passwordEncoder.encode("Pass123!"), Role.STUDENT, "Student One"));
        student2 = userRepository.save(User.create("student2@dd.local", passwordEncoder.encode("Pass123!"), Role.STUDENT, "Student Two"));
        admin = userRepository.save(User.create("admin@dd.local", passwordEncoder.encode("Pass123!"), Role.ADMIN, "Admin User"));

        teacher1Token = TestTokens.accessToken(jwtService, teacher1);
        teacher2Token = TestTokens.accessToken(jwtService, teacher2);
        student1Token = TestTokens.accessToken(jwtService, student1);
        student2Token = TestTokens.accessToken(jwtService, student2);
        adminToken = TestTokens.accessToken(jwtService, admin);
    }

    // ==========================================
    // CourseController tests
    // ==========================================

    @Test
    void createCourseAsTeacher201() {
        CreateCourseRequest req = new CreateCourseRequest("CS101", "Introduction to Computer Science");
        ResponseEntity<CourseDto> resp = restTemplate.exchange(
                "/api/courses",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeader(teacher1Token)),
                CourseDto.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().id()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("CS101");
        assertThat(resp.getBody().title()).isEqualTo("Introduction to Computer Science");
        assertThat(resp.getBody().students()).isEqualTo(0);
    }

    @Test
    void createCourseAsStudentForbidden403() {
        CreateCourseRequest req = new CreateCourseRequest("CS102", "Data Structures");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/courses",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeader(student1Token)),
                String.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void createCourseDuplicateCodeConflict409() throws Exception {
        CreateCourseRequest req = new CreateCourseRequest("CS101", "Course One");
        restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(req, authHeader(teacher1Token)), CourseDto.class);

        CreateCourseRequest dupReq = new CreateCourseRequest("CS101", "Duplicate Code Course");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/courses",
                HttpMethod.POST,
                new HttpEntity<>(dupReq, authHeader(teacher1Token)),
                String.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("code").asText()).isEqualTo(ErrorCodes.VERSION_CONFLICT);
    }

    @Test
    void createCourseValidationFailed400() {
        CreateCourseRequest invalid = new CreateCourseRequest("", "");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/courses",
                HttpMethod.POST,
                new HttpEntity<>(invalid, authHeader(teacher1Token)),
                String.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listCoursesTeacherShowsOwnCoursesAndStudentShowsEnrolledCourses() {
        // Teacher 1 creates 2 courses
        CourseDto c1 = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS101", "CS 101"), authHeader(teacher1Token)), CourseDto.class).getBody();
        CourseDto c2 = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS102", "CS 102"), authHeader(teacher1Token)), CourseDto.class).getBody();
        // Teacher 2 creates 1 course
        CourseDto c3 = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("MATH201", "Math 201"), authHeader(teacher2Token)), CourseDto.class).getBody();

        // Enroll student1 in c1 and c3
        restTemplate.exchange("/api/courses/" + c1.id() + "/enrollments", HttpMethod.POST, new HttpEntity<>(new EnrollRequest(List.of(student1.getEmail())), authHeader(teacher1Token)), EnrollResult.class);
        restTemplate.exchange("/api/courses/" + c3.id() + "/enrollments", HttpMethod.POST, new HttpEntity<>(new EnrollRequest(List.of(student1.getEmail())), authHeader(teacher2Token)), EnrollResult.class);

        // Teacher 1 lists courses
        ResponseEntity<List<CourseDto>> teacherListResp = restTemplate.exchange(
                "/api/courses",
                HttpMethod.GET,
                new HttpEntity<>(authHeader(teacher1Token)),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(teacherListResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(teacherListResp.getBody()).hasSize(2);
        assertThat(teacherListResp.getBody()).extracting(CourseDto::code).containsExactlyInAnyOrder("CS101", "CS102");
        CourseDto c1Dto = teacherListResp.getBody().stream().filter(c -> c.code().equals("CS101")).findFirst().orElseThrow();
        assertThat(c1Dto.students()).isEqualTo(1);

        // Student 1 lists courses
        ResponseEntity<List<CourseDto>> studentListResp = restTemplate.exchange(
                "/api/courses",
                HttpMethod.GET,
                new HttpEntity<>(authHeader(student1Token)),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(studentListResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(studentListResp.getBody()).hasSize(2);
        assertThat(studentListResp.getBody()).extracting(CourseDto::code).containsExactlyInAnyOrder("CS101", "MATH201");
    }

    @Test
    void enrollStudentsAsOwnerTeacherAndHandleDuplicatesAndUnknown() {
        CourseDto course = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS101", "CS 101"), authHeader(teacher1Token)), CourseDto.class).getBody();

        EnrollRequest req = new EnrollRequest(List.of("student1@dd.local", "student2@dd.local", "unknown@dd.local"));
        ResponseEntity<EnrollResult> resp = restTemplate.exchange(
                "/api/courses/" + course.id() + "/enrollments",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeader(teacher1Token)),
                EnrollResult.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().enrolled()).isEqualTo(2);
        assertThat(resp.getBody().alreadyEnrolled()).isEqualTo(0);
        assertThat(resp.getBody().unknownEmails()).containsExactly("unknown@dd.local");

        // Re-enroll with same emails
        ResponseEntity<EnrollResult> reResp = restTemplate.exchange(
                "/api/courses/" + course.id() + "/enrollments",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeader(teacher1Token)),
                EnrollResult.class
        );
        assertThat(reResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reResp.getBody()).isNotNull();
        assertThat(reResp.getBody().enrolled()).isEqualTo(0);
        assertThat(reResp.getBody().alreadyEnrolled()).isEqualTo(2);
        assertThat(reResp.getBody().unknownEmails()).containsExactly("unknown@dd.local");
    }

    @Test
    void enrollStudentsNonOwnerTeacherForbidden403() {
        CourseDto course = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS101", "CS 101"), authHeader(teacher1Token)), CourseDto.class).getBody();

        EnrollRequest req = new EnrollRequest(List.of("student1@dd.local"));
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/courses/" + course.id() + "/enrollments",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeader(teacher2Token)),
                String.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ==========================================
    // AssignmentController tests
    // ==========================================

    @Test
    void createAssignmentAsOwnerTeacher201() {
        CourseDto course = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS101", "CS 101"), authHeader(teacher1Token)), CourseDto.class).getBody();

        Instant deadline = SIMULATED_TIME.plus(Duration.ofDays(7));
        CreateAssignmentRequest req = new CreateAssignmentRequest("Lab 1", deadline, 900, 10485760L, List.of("application/pdf"));

        ResponseEntity<AssignmentDto> resp = restTemplate.exchange(
                "/api/courses/" + course.id() + "/assignments",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeader(teacher1Token)),
                AssignmentDto.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().id()).isNotNull();
        assertThat(resp.getBody().courseId()).isEqualTo(course.id());
        assertThat(resp.getBody().title()).isEqualTo("Lab 1");
        assertThat(resp.getBody().deadlineAt()).isEqualTo(deadline);
        assertThat(resp.getBody().graceSeconds()).isEqualTo(900);
        assertThat(resp.getBody().graceEndsAt()).isEqualTo(deadline.plusSeconds(900));
        assertThat(resp.getBody().maxBytes()).isEqualTo(10485760L);
        assertThat(resp.getBody().allowedTypes()).containsExactly("application/pdf");
        assertThat(resp.getBody().version()).isEqualTo(0L);
    }

    @Test
    void createAssignmentBoundaryValues() throws Exception {
        CourseDto course = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS101", "CS 101"), authHeader(teacher1Token)), CourseDto.class).getBody();
        Instant deadline = SIMULATED_TIME.plus(Duration.ofDays(7));

        // 1. graceSeconds: 3600 -> 201
        CreateAssignmentRequest reqGrace3600 = new CreateAssignmentRequest("Lab Grace 3600", deadline, 3600, 10485760L, List.of("application/pdf"));
        ResponseEntity<AssignmentDto> respGrace3600 = restTemplate.exchange(
                "/api/courses/" + course.id() + "/assignments",
                HttpMethod.POST,
                new HttpEntity<>(reqGrace3600, authHeader(teacher1Token)),
                AssignmentDto.class
        );
        assertThat(respGrace3600.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(respGrace3600.getBody()).isNotNull();
        assertThat(respGrace3600.getBody().graceSeconds()).isEqualTo(3600);

        // 2. graceSeconds: 3601 -> 400 VALIDATION_FAILED with field "graceSeconds" in errors list
        CreateAssignmentRequest reqGrace3601 = new CreateAssignmentRequest("Lab Grace 3601", deadline, 3601, 10485760L, List.of("application/pdf"));
        ResponseEntity<String> respGrace3601 = restTemplate.exchange(
                "/api/courses/" + course.id() + "/assignments",
                HttpMethod.POST,
                new HttpEntity<>(reqGrace3601, authHeader(teacher1Token)),
                String.class
        );
        assertThat(respGrace3601.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode jsonGrace3601 = objectMapper.readTree(respGrace3601.getBody());
        assertThat(jsonGrace3601.path("code").asText()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
        assertThat(jsonGrace3601.path("errors")).isNotEmpty();
        boolean hasGraceField = false;
        for (JsonNode err : jsonGrace3601.path("errors")) {
            if ("graceSeconds".equals(err.path("field").asText())) {
                hasGraceField = true;
                break;
            }
        }
        assertThat(hasGraceField).isTrue();

        // 3. maxBytes: 26214400 -> 201
        CreateAssignmentRequest reqBytesMax = new CreateAssignmentRequest("Lab Max Bytes", deadline, 900, 26214400L, List.of("application/pdf"));
        ResponseEntity<AssignmentDto> respBytesMax = restTemplate.exchange(
                "/api/courses/" + course.id() + "/assignments",
                HttpMethod.POST,
                new HttpEntity<>(reqBytesMax, authHeader(teacher1Token)),
                AssignmentDto.class
        );
        assertThat(respBytesMax.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(respBytesMax.getBody()).isNotNull();
        assertThat(respBytesMax.getBody().maxBytes()).isEqualTo(26214400L);

        // 4. maxBytes: 26214401 -> 400 VALIDATION_FAILED with field "maxBytes" in errors list
        CreateAssignmentRequest reqBytesOver = new CreateAssignmentRequest("Lab Over Max Bytes", deadline, 900, 26214401L, List.of("application/pdf"));
        ResponseEntity<String> respBytesOver = restTemplate.exchange(
                "/api/courses/" + course.id() + "/assignments",
                HttpMethod.POST,
                new HttpEntity<>(reqBytesOver, authHeader(teacher1Token)),
                String.class
        );
        assertThat(respBytesOver.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode jsonBytesOver = objectMapper.readTree(respBytesOver.getBody());
        assertThat(jsonBytesOver.path("code").asText()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
        assertThat(jsonBytesOver.path("errors")).isNotEmpty();
        boolean hasBytesField = false;
        for (JsonNode err : jsonBytesOver.path("errors")) {
            if ("maxBytes".equals(err.path("field").asText())) {
                hasBytesField = true;
                break;
            }
        }
        assertThat(hasBytesField).isTrue();
    }

    @Test
    void createAssignmentNonOwnerTeacherForbidden403() {
        CourseDto course = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS101", "CS 101"), authHeader(teacher1Token)), CourseDto.class).getBody();

        Instant deadline = SIMULATED_TIME.plus(Duration.ofDays(7));
        CreateAssignmentRequest req = new CreateAssignmentRequest("Lab 1", deadline, 900, 10485760L, List.of("application/pdf"));

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/courses/" + course.id() + "/assignments",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeader(teacher2Token)),
                String.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void createAssignmentValidationFailed400() {
        CourseDto course = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS101", "CS 101"), authHeader(teacher1Token)), CourseDto.class).getBody();

        // Invalid MIME type pattern
        CreateAssignmentRequest invalidMime = new CreateAssignmentRequest("Lab 1", SIMULATED_TIME.plus(Duration.ofDays(7)), 900, 10485760L, List.of("not-a-valid-mime-type"));
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/courses/" + course.id() + "/assignments",
                HttpMethod.POST,
                new HttpEntity<>(invalidMime, authHeader(teacher1Token)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Negative grace seconds
        CreateAssignmentRequest negGrace = new CreateAssignmentRequest("Lab 1", SIMULATED_TIME.plus(Duration.ofDays(7)), -1, 10485760L, List.of("application/pdf"));
        ResponseEntity<String> resp2 = restTemplate.exchange(
                "/api/courses/" + course.id() + "/assignments",
                HttpMethod.POST,
                new HttpEntity<>(negGrace, authHeader(teacher1Token)),
                String.class
        );
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getAssignmentPermissionsOwnerEnrolledUnenrolledOtherTeacher() {
        CourseDto course = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS101", "CS 101"), authHeader(teacher1Token)), CourseDto.class).getBody();
        restTemplate.exchange("/api/courses/" + course.id() + "/enrollments", HttpMethod.POST, new HttpEntity<>(new EnrollRequest(List.of(student1.getEmail())), authHeader(teacher1Token)), EnrollResult.class);

        AssignmentDto assignment = restTemplate.exchange("/api/courses/" + course.id() + "/assignments", HttpMethod.POST, new HttpEntity<>(new CreateAssignmentRequest("Lab 1", SIMULATED_TIME.plus(Duration.ofDays(7)), 900, 10485760L, List.of("application/pdf")), authHeader(teacher1Token)), AssignmentDto.class).getBody();

        // Owning teacher -> 200
        ResponseEntity<AssignmentDto> ownerResp = restTemplate.exchange("/api/assignments/" + assignment.id(), HttpMethod.GET, new HttpEntity<>(authHeader(teacher1Token)), AssignmentDto.class);
        assertThat(ownerResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Enrolled student -> 200
        ResponseEntity<AssignmentDto> studentResp = restTemplate.exchange("/api/assignments/" + assignment.id(), HttpMethod.GET, new HttpEntity<>(authHeader(student1Token)), AssignmentDto.class);
        assertThat(studentResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Unenrolled student -> 403
        ResponseEntity<String> unenrolledResp = restTemplate.exchange("/api/assignments/" + assignment.id(), HttpMethod.GET, new HttpEntity<>(authHeader(student2Token)), String.class);
        assertThat(unenrolledResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Other teacher -> 403
        ResponseEntity<String> otherTeacherResp = restTemplate.exchange("/api/assignments/" + assignment.id(), HttpMethod.GET, new HttpEntity<>(authHeader(teacher2Token)), String.class);
        assertThat(otherTeacherResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void updateAssignmentAsOwnerTeacher200() {
        CourseDto course = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS101", "CS 101"), authHeader(teacher1Token)), CourseDto.class).getBody();
        AssignmentDto assignment = restTemplate.exchange("/api/courses/" + course.id() + "/assignments", HttpMethod.POST, new HttpEntity<>(new CreateAssignmentRequest("Lab 1", SIMULATED_TIME.plus(Duration.ofDays(7)), 900, 10485760L, List.of("application/pdf")), authHeader(teacher1Token)), AssignmentDto.class).getBody();

        Instant newDeadline = SIMULATED_TIME.plus(Duration.ofDays(10));
        UpdateAssignmentRequest updateReq = new UpdateAssignmentRequest("Lab 1 Revised", newDeadline, 600, 5242880L, List.of("application/zip"), assignment.version());

        ResponseEntity<AssignmentDto> resp = restTemplate.exchange(
                "/api/assignments/" + assignment.id(),
                HttpMethod.PATCH,
                new HttpEntity<>(updateReq, authHeader(teacher1Token)),
                AssignmentDto.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().title()).isEqualTo("Lab 1 Revised");
        assertThat(resp.getBody().deadlineAt()).isEqualTo(newDeadline);
        assertThat(resp.getBody().graceSeconds()).isEqualTo(600);
        assertThat(resp.getBody().maxBytes()).isEqualTo(5242880L);
        assertThat(resp.getBody().allowedTypes()).containsExactly("application/zip");
        assertThat(resp.getBody().version()).isEqualTo(1L);
    }

    @Test
    void updateAssignmentVersionConflict409() throws Exception {
        CourseDto course = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS101", "CS 101"), authHeader(teacher1Token)), CourseDto.class).getBody();
        AssignmentDto assignment = restTemplate.exchange("/api/courses/" + course.id() + "/assignments", HttpMethod.POST, new HttpEntity<>(new CreateAssignmentRequest("Lab 1", SIMULATED_TIME.plus(Duration.ofDays(7)), 900, 10485760L, List.of("application/pdf")), authHeader(teacher1Token)), AssignmentDto.class).getBody();

        // Stale version (e.g. 99L instead of 0L)
        UpdateAssignmentRequest staleReq = new UpdateAssignmentRequest("Lab 1 Revised", SIMULATED_TIME.plus(Duration.ofDays(10)), 600, 5242880L, List.of("application/zip"), 99L);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/assignments/" + assignment.id(),
                HttpMethod.PATCH,
                new HttpEntity<>(staleReq, authHeader(teacher1Token)),
                String.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("code").asText()).isEqualTo(ErrorCodes.VERSION_CONFLICT);
    }

    @Test
    void updateAssignmentAfterDeadlineWrongState409() throws Exception {
        CourseDto course = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS101", "CS 101"), authHeader(teacher1Token)), CourseDto.class).getBody();
        Instant deadline = SIMULATED_TIME.plus(Duration.ofHours(2));
        AssignmentDto assignment = restTemplate.exchange("/api/courses/" + course.id() + "/assignments", HttpMethod.POST, new HttpEntity<>(new CreateAssignmentRequest("Lab 1", deadline, 900, 10485760L, List.of("application/pdf")), authHeader(teacher1Token)), AssignmentDto.class).getBody();

        // Move clock past deadline
        clock.set(deadline.plusSeconds(10));

        UpdateAssignmentRequest updateReq = new UpdateAssignmentRequest("Lab 1 Late Edit", deadline.plus(Duration.ofDays(1)), 900, 10485760L, List.of("application/pdf"), assignment.version());
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/assignments/" + assignment.id(),
                HttpMethod.PATCH,
                new HttpEntity<>(updateReq, authHeader(teacher1Token)),
                String.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("code").asText()).isEqualTo(ErrorCodes.WRONG_STATE);
    }

    @Test
    void myAssignmentsOrderedByDeadlineWithLiveSubmission() {
        CourseDto course1 = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS101", "CS 101"), authHeader(teacher1Token)), CourseDto.class).getBody();
        CourseDto course2 = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("MATH201", "Math 201"), authHeader(teacher2Token)), CourseDto.class).getBody();

        // Enroll student1 in both courses
        restTemplate.exchange("/api/courses/" + course1.id() + "/enrollments", HttpMethod.POST, new HttpEntity<>(new EnrollRequest(List.of(student1.getEmail())), authHeader(teacher1Token)), EnrollResult.class);
        restTemplate.exchange("/api/courses/" + course2.id() + "/enrollments", HttpMethod.POST, new HttpEntity<>(new EnrollRequest(List.of(student1.getEmail())), authHeader(teacher2Token)), EnrollResult.class);

        // Create 3 assignments with distinct deadlines: Lab 1 (due T+5d), Lab 2 (due T+1d), Lab 3 (due T+3d)
        AssignmentDto a1 = restTemplate.exchange("/api/courses/" + course1.id() + "/assignments", HttpMethod.POST, new HttpEntity<>(new CreateAssignmentRequest("Lab 1", SIMULATED_TIME.plus(Duration.ofDays(5)), 900, 10485760L, List.of("application/pdf")), authHeader(teacher1Token)), AssignmentDto.class).getBody();
        AssignmentDto a2 = restTemplate.exchange("/api/courses/" + course1.id() + "/assignments", HttpMethod.POST, new HttpEntity<>(new CreateAssignmentRequest("Lab 2", SIMULATED_TIME.plus(Duration.ofDays(1)), 900, 10485760L, List.of("application/pdf")), authHeader(teacher1Token)), AssignmentDto.class).getBody();
        AssignmentDto a3 = restTemplate.exchange("/api/courses/" + course2.id() + "/assignments", HttpMethod.POST, new HttpEntity<>(new CreateAssignmentRequest("Lab 3", SIMULATED_TIME.plus(Duration.ofDays(3)), 900, 10485760L, List.of("application/pdf")), authHeader(teacher2Token)), AssignmentDto.class).getBody();

        // Insert a live submission for student1 on a2 (Lab 2)
        UUID subId = UUID.randomUUID();
        insertSubmission(subId, a2.id(), student1.getId(), Status.COMMITTED, Timing.ON_TIME, SIMULATED_TIME);

        ResponseEntity<List<StudentAssignmentDto>> resp = restTemplate.exchange(
                "/api/me/assignments",
                HttpMethod.GET,
                new HttpEntity<>(authHeader(student1Token)),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<StudentAssignmentDto> items = resp.getBody();
        assertThat(items).hasSize(3);

        // Ordered by deadline ascending: a2 (T+1d), a3 (T+3d), a1 (T+5d)
        assertThat(items.get(0).assignment().id()).isEqualTo(a2.id());
        assertThat(items.get(0).courseCode()).isEqualTo("CS101");
        assertThat(items.get(0).mySubmission()).isNotNull();
        assertThat(items.get(0).mySubmission().submissionId()).isEqualTo(subId);
        assertThat(items.get(0).mySubmission().status()).isEqualTo("COMMITTED");
        assertThat(items.get(0).mySubmission().timing()).isEqualTo("ON_TIME");
        assertThat(items.get(0).mySubmission().stampedAt()).isEqualTo(SIMULATED_TIME);

        assertThat(items.get(1).assignment().id()).isEqualTo(a3.id());
        assertThat(items.get(1).courseCode()).isEqualTo("MATH201");
        assertThat(items.get(1).mySubmission()).isNull();

        assertThat(items.get(2).assignment().id()).isEqualTo(a1.id());
        assertThat(items.get(2).courseCode()).isEqualTo("CS101");
        assertThat(items.get(2).mySubmission()).isNull();
    }

    // ==========================================
    // TimeController tests
    // ==========================================

    @Test
    void timeEndpointPublicAndReturnsEpochMsWithNoStore() {
        ResponseEntity<TimeResponse> resp = restTemplate.getForEntity("/api/time", TimeResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().epochMs()).isEqualTo(SIMULATED_TIME.toEpochMilli());
    }

    // ==========================================
    // ExportController tests (Arm C - submissions table)
    // ==========================================

    @Test
    void exportJsonAndCsvAsOwnerTeacherAndAdmin() throws Exception {
        CourseDto course = restTemplate.exchange("/api/courses", HttpMethod.POST, new HttpEntity<>(new CreateCourseRequest("CS101", "CS 101"), authHeader(teacher1Token)), CourseDto.class).getBody();
        AssignmentDto assignment = restTemplate.exchange("/api/courses/" + course.id() + "/assignments", HttpMethod.POST, new HttpEntity<>(new CreateAssignmentRequest("Lab 1", SIMULATED_TIME.plus(Duration.ofDays(7)), 900, 10485760L, List.of("application/pdf")), authHeader(teacher1Token)), AssignmentDto.class).getBody();

        UUID sub1 = UUID.randomUUID();
        UUID sub2 = UUID.randomUUID();
        insertSubmission(sub1, assignment.id(), student1.getId(), Status.UPLOADED, Timing.ON_TIME, SIMULATED_TIME);
        insertSubmission(sub2, assignment.id(), student2.getId(), Status.EXPIRED, Timing.LATE, SIMULATED_TIME.plusSeconds(10));

        // Export JSON as owner teacher
        ResponseEntity<List<ExportRow>> jsonResp = restTemplate.exchange(
                "/api/assignments/" + assignment.id() + "/export?format=json",
                HttpMethod.GET,
                new HttpEntity<>(authHeader(teacher1Token)),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(jsonResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jsonResp.getBody()).hasSize(2);

        ExportRow r1 = jsonResp.getBody().get(0);
        assertThat(r1.studentId()).isEqualTo(student1.getId());
        assertThat(r1.studentEmail()).isEqualTo("student1@dd.local");
        assertThat(r1.submissionId()).isEqualTo(sub1);
        assertThat(r1.status()).isEqualTo("UPLOADED");
        assertThat(r1.timing()).isEqualTo("ON_TIME");
        assertThat(r1.stampSource()).isEqualTo("COMMIT");

        ExportRow r2 = jsonResp.getBody().get(1);
        assertThat(r2.studentId()).isEqualTo(student2.getId());
        assertThat(r2.studentEmail()).isEqualTo("student2@dd.local");
        assertThat(r2.submissionId()).isEqualTo(sub2);
        assertThat(r2.status()).isEqualTo("EXPIRED");
        assertThat(r2.timing()).isEqualTo("LATE");
        assertThat(r2.stampSource()).isEqualTo("COMMIT");

        // Export CSV as Admin
        ResponseEntity<String> csvResp = restTemplate.exchange(
                "/api/assignments/" + assignment.id() + "/export?format=csv",
                HttpMethod.GET,
                new HttpEntity<>(authHeader(adminToken)),
                String.class
        );
        assertThat(csvResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csvResp.getHeaders().getContentType().toString()).contains("text/csv");
        String csvBody = csvResp.getBody();
        assertThat(csvBody).startsWith("studentId,studentEmail,submissionId,stampedAt,uploadedAt,status,timing,stampSource\n");
        assertThat(csvBody).contains(student1.getId().toString());
        assertThat(csvBody).contains("student1@dd.local");
        assertThat(csvBody).contains("COMMIT");

        // Export as student -> 403
        ResponseEntity<String> studentForbidden = restTemplate.exchange(
                "/api/assignments/" + assignment.id() + "/export",
                HttpMethod.GET,
                new HttpEntity<>(authHeader(student1Token)),
                String.class
        );
        assertThat(studentForbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Export as other teacher -> 403
        ResponseEntity<String> teacher2Forbidden = restTemplate.exchange(
                "/api/assignments/" + assignment.id() + "/export",
                HttpMethod.GET,
                new HttpEntity<>(authHeader(teacher2Token)),
                String.class
        );
        assertThat(teacher2Forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void insertSubmission(UUID id, UUID assignmentId, UUID studentId, Status status, Timing timing, Instant committedAt) {
        jdbcTemplate.update(
                "INSERT INTO submissions (id, assignment_id, student_id, idempotency_key, request_hash, sha256, size_bytes, content_type, object_path, status, timing, committed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::submission_status, ?::submission_timing, ?)",
                id, assignmentId, studentId, UUID.randomUUID(), "reqhash", "a".repeat(64), 1024L, "application/pdf", "obj/path/" + id,
                status.name(), timing.name(), OffsetDateTime.ofInstant(committedAt, ZoneOffset.UTC)
        );
    }

    private static HttpHeaders authHeader(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
