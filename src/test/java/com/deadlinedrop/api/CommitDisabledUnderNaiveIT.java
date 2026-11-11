package com.deadlinedrop.api;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.dto.AuthDtos.TokenResponse;
import com.deadlinedrop.api.dto.CommitDtos.CommitRequest;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The commit endpoint belongs to arm C only: under profile naive it must not exist. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"test", "naive"})
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "JWT_SECRET=dGVzdC1zZWNyZXQta2V5LXdpdGgtYXQtbGVhc3QtMzItYnl0ZXMtbG9uZw=="
})
class CommitDisabledUnderNaiveIT {

    private final TestRestTemplate restTemplate;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    CommitDisabledUnderNaiveIT(
            TestRestTemplate restTemplate,
            UserRepository userRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder) {
        this.restTemplate = restTemplate;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    @Test
    void commitEndpointReturns404UnderNaiveProfile() {
        userRepository.deleteAll();
        User student = userRepository.save(User.create(
                "naive-commit@dd.local", passwordEncoder.encode("Password123!"), Role.STUDENT, "Student"));
        TokenResponse token = jwtService.issue(student);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token.accessToken());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/assignments/" + UUID.randomUUID() + "/commits",
                new HttpEntity<>(new CommitRequest("a".repeat(64), 10, "application/pdf"), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
