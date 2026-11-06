package com.deadlinedrop.security;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.dto.AuthDtos.LoginRequest;
import com.deadlinedrop.api.dto.AuthDtos.MeResponse;
import com.deadlinedrop.api.dto.AuthDtos.TokenResponse;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AuthIT.TestTeacherController.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "JWT_SECRET=dGVzdC1zZWNyZXQta2V5LXdpdGgtYXQtbGVhc3QtMzItYnl0ZXMtbG9uZw=="
})
class AuthIT {

    @RestController
    static class TestTeacherController {
        @GetMapping("/api/test-teacher-only")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<String> teacherOnly() {
            return ResponseEntity.ok("teacher-data");
        }
    }

    private final TestRestTemplate restTemplate;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    private User student;
    private User teacher;

    @Autowired
    public AuthIT(
            TestRestTemplate restTemplate,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        student = userRepository.save(User.create(
                "student@dd.local",
                passwordEncoder.encode("Password123!"),
                Role.STUDENT,
                "Student Name"
        ));

        teacher = userRepository.save(User.create(
                "teacher@dd.local",
                passwordEncoder.encode("Password123!"),
                Role.TEACHER,
                "Teacher Name"
        ));
    }

    @Test
    void login200WithExpiresAtAbout12HoursAhead() {
        LoginRequest request = new LoginRequest("student@dd.local", "Password123!");
        ResponseEntity<TokenResponse> response = restTemplate.postForEntity(
                "/api/auth/login",
                request,
                TokenResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TokenResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.accessToken()).isNotBlank();
        assertThat(body.role()).isEqualTo("STUDENT");
        assertThat(body.displayName()).isEqualTo("Student Name");

        Duration timeUntilExpiry = Duration.between(Instant.now(), body.expiresAt());
        assertThat(timeUntilExpiry).isBetween(Duration.ofHours(11).plusMinutes(50), Duration.ofHours(12).plusMinutes(10));
    }

    @Test
    void wrongPasswordReturns401InvalidCredentials() {
        LoginRequest request = new LoginRequest("student@dd.local", "WrongPassword!");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/auth/login",
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(response.getBody()).contains("\"code\":\"INVALID_CREDENTIALS\"");
        assertThat(response.getBody()).contains("Wrong email or password");
    }

    @Test
    void unknownEmailReturns401WithBodyIdenticalApartFromRequestIdAndInstance() throws Exception {
        LoginRequest wrongPassReq = new LoginRequest("student@dd.local", "WrongPassword!");
        ResponseEntity<String> wrongPassResp = restTemplate.postForEntity(
                "/api/auth/login",
                wrongPassReq,
                String.class
        );

        LoginRequest unknownEmailReq = new LoginRequest("unknown@dd.local", "WrongPassword!");
        ResponseEntity<String> unknownEmailResp = restTemplate.postForEntity(
                "/api/auth/login",
                unknownEmailReq,
                String.class
        );

        assertThat(wrongPassResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmailResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        JsonNode wrongPassNode = objectMapper.readTree(wrongPassResp.getBody());
        JsonNode unknownEmailNode = objectMapper.readTree(unknownEmailResp.getBody());

        assertThat(unknownEmailNode.get("type").asText()).isEqualTo(wrongPassNode.get("type").asText());
        assertThat(unknownEmailNode.get("title").asText()).isEqualTo(wrongPassNode.get("title").asText());
        assertThat(unknownEmailNode.get("status").asInt()).isEqualTo(wrongPassNode.get("status").asInt());
        assertThat(unknownEmailNode.get("detail").asText()).isEqualTo(wrongPassNode.get("detail").asText());
        assertThat(unknownEmailNode.get("code").asText()).isEqualTo(wrongPassNode.get("code").asText());

        assertThat(wrongPassNode.get("requestId").asText()).isNotBlank();
        assertThat(unknownEmailNode.get("requestId").asText()).isNotBlank();
        // Different requests generate distinct requestIds
        assertThat(unknownEmailNode.get("requestId").asText()).isNotEqualTo(wrongPassNode.get("requestId").asText());
    }

    @Test
    void meWithoutTokenReturns401Unauthenticated() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(response.getBody()).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    @Test
    void tokenWithExpInThePastReturns401() {
        Instant now = Instant.now();
        TokenResponse expiredToken = jwtService.issue(
                student,
                now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofHours(1))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(expiredToken.accessToken());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/me",
                HttpMethod.GET,
                entity,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    @Test
    void studentCallingTeacherMethodReturns403Forbidden() {
        TokenResponse studentToken = jwtService.issue(student);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(studentToken.accessToken());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> forbiddenResp = restTemplate.exchange(
                "/api/test-teacher-only",
                HttpMethod.GET,
                entity,
                String.class
        );

        assertThat(forbiddenResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbiddenResp.getHeaders().getContentType()).isNotNull();
        assertThat(forbiddenResp.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(forbiddenResp.getBody()).contains("\"code\":\"FORBIDDEN\"");

        // Also verify teacher has access
        TokenResponse teacherToken = jwtService.issue(teacher);
        HttpHeaders teacherHeaders = new HttpHeaders();
        teacherHeaders.setBearerAuth(teacherToken.accessToken());
        HttpEntity<Void> teacherEntity = new HttpEntity<>(teacherHeaders);

        ResponseEntity<String> allowedResp = restTemplate.exchange(
                "/api/test-teacher-only",
                HttpMethod.GET,
                teacherEntity,
                String.class
        );
        assertThat(allowedResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowedResp.getBody()).isEqualTo("teacher-data");
    }

    @Test
    void meWithValidTokenReturnsMeResponse() {
        TokenResponse studentToken = jwtService.issue(student);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(studentToken.accessToken());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<MeResponse> response = restTemplate.exchange(
                "/api/me",
                HttpMethod.GET,
                entity,
                MeResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MeResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(student.getId());
        assertThat(body.email()).isEqualTo("student@dd.local");
        assertThat(body.displayName()).isEqualTo("Student Name");
        assertThat(body.role()).isEqualTo("STUDENT");
    }
}
