package com.deadlinedrop.api;

import com.deadlinedrop.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** The public config endpoint and the OpenAPI document (switched on here as local and cloud do). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ConfigIT {

    private final TestRestTemplate rest;
    private final ObjectMapper objectMapper;

    @Autowired
    ConfigIT(TestRestTemplate rest, ObjectMapper objectMapper) {
        this.rest = rest;
        this.objectMapper = objectMapper;
    }

    @Test
    void configIsPublicAndDescribesArmC() {
        ResponseEntity<String> r = rest.getForEntity("/api/config", String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(r.getBody());
        assertThat(json.get("arm").asText()).isEqualTo("C");
        assertThat(json.get("armLabel").asText()).isEqualTo("DeadlineDrop: time locked when you commit");
        assertThat(json.get("maxBytesCap").asLong()).isEqualTo(26_214_400L);
        assertThat(json.get("consentVersion").asText()).isEqualTo("v1");
        assertThat(json.get("serverEpochMs").asLong()).isPositive();
    }

    @Test
    void openApiDocumentListsTheNewEndpointsWithoutAToken() {
        ResponseEntity<String> r = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode paths = objectMapper.readTree(r.getBody()).get("paths");
        assertThat(paths.has("/api/submissions/{id}/upload-url")).isTrue();
        assertThat(paths.has("/api/telemetry")).isTrue();
        assertThat(paths.has("/api/survey")).isTrue();
        assertThat(paths.has("/api/config")).isTrue();
    }
}
