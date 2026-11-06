package com.deadlinedrop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke integration test: verifies the application context loads,
 * the health endpoint returns HTTP 200 with status UP, and unauthenticated
 * API endpoints return 401 ProblemDetail with an X-Request-Id header.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ApplicationSmokeIT {

    private final TestRestTemplate restTemplate;

    public ApplicationSmokeIT(@Autowired TestRestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Test
    void contextLoads() {
        // passes if the context starts without exception
    }

    @Test
    void actuatorHealthReturns200WithStatusUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void unauthenticatedApiRequestReturns401WithProblemDetailAndRequestIdHeader() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/anything", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        assertThat(response.getBody()).contains("\"code\":\"UNAUTHENTICATED\"");
        assertThat(response.getBody()).contains("\"requestId\":");
    }
}
