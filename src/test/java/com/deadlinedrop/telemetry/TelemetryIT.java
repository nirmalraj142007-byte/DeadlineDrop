package com.deadlinedrop.telemetry;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.api.dto.TelemetryDtos.SurveyRequest;
import com.deadlinedrop.api.dto.TelemetryDtos.TelemetryRequest;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.security.JwtService;
import com.deadlinedrop.support.MutableClock;
import com.deadlinedrop.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** POST /api/telemetry and POST /api/survey: consent gate, anonymisation, idempotent inserts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TelemetryIT.TestClockConfig.class})
class TelemetryIT {

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.of(Instant.parse("2026-11-20T12:00:00Z"));
        }
    }

    private final TestRestTemplate rest;
    private final UserRepository users;
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String salt;

    private User student;
    private User otherStudent;
    private User teacher;
    private final UUID assignmentId = UUID.randomUUID();

    @Autowired
    TelemetryIT(TestRestTemplate rest, UserRepository users, JwtService jwtService, JdbcTemplate jdbc,
            ObjectMapper objectMapper, @Value("${dd.telemetry.salt}") String salt) {
        this.rest = rest;
        this.users = users;
        this.jwtService = jwtService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.salt = salt;
    }

    @BeforeEach
    void setUp() {
        clean();
        student = users.save(User.create("tele-student1@dd.local", "unused-hash", Role.STUDENT, "Tele One"));
        otherStudent = users.save(User.create("tele-student2@dd.local", "unused-hash", Role.STUDENT, "Tele Two"));
        teacher = users.save(User.create("tele-teacher@dd.local", "unused-hash", Role.TEACHER, "Tele Teacher"));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM telemetry_events");
        jdbc.update("DELETE FROM survey_responses");
        users.deleteAll();
    }

    private HttpEntity<Object> body(User as, Object payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (as != null) {
            headers.setBearerAuth(TestTokens.accessToken(jwtService, as));
        }
        return new HttpEntity<>(payload, headers);
    }

    private ResponseEntity<String> postTelemetry(User as, TelemetryRequest r) {
        return rest.postForEntity("/api/telemetry", body(as, r), String.class);
    }

    private ResponseEntity<String> postSurvey(User as, SurveyRequest r) {
        return rest.postForEntity("/api/survey", body(as, r), String.class);
    }

    private TelemetryRequest telemetry(String consent, Double mbps, String network) {
        return new TelemetryRequest(assignmentId, consent, mbps, 2048L, -120, 35, network);
    }

    private String code(ResponseEntity<String> r) {
        return objectMapper.readTree(r.getBody()).get("code").asText();
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private String expectedAnonId(User u) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(u.getId().toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void missingOrBlankConsentIs400ConsentRequiredAndStoresNothing() {
        for (String consent : new String[] {null, "", "   "}) {
            ResponseEntity<String> r = postTelemetry(student, telemetry(consent, 12.5, "HOSTEL_WIFI"));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(code(r)).isEqualTo(ErrorCodes.CONSENT_REQUIRED);
        }
        assertThat(count("telemetry_events")).isZero();
    }

    @Test
    void acceptedTelemetryIsStoredUnderTheHmacOfTheStudentIdAndNothingElseIdentifying() throws Exception {
        ResponseEntity<String> r = postTelemetry(student, telemetry("v1", 12.5, "HOSTEL_WIFI"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM telemetry_events");
        assertThat(row.get("anon_id").toString().trim()).isEqualTo(expectedAnonId(student)).hasSize(64);
        assertThat(row.toString()).doesNotContain(student.getId().toString()).doesNotContain(student.getEmail());
        assertThat(row.get("assignment_id")).isEqualTo(assignmentId);
        assertThat(row.get("consent_version")).isEqualTo("v1");
        assertThat(((Number) row.get("upload_mbps")).doubleValue()).isEqualTo(12.5);
        assertThat(row.get("size_bytes")).isEqualTo(2048L);
        assertThat(row.get("click_offset_ms")).isEqualTo(-120);
        assertThat(row.get("hash_ms")).isEqualTo(35);
        assertThat(row.get("network_type")).isEqualTo("HOSTEL_WIFI");
    }

    @Test
    void aSecondPostForTheSameAssignmentIsAcceptedButIgnored() {
        assertThat(postTelemetry(student, telemetry("v1", 12.5, "HOSTEL_WIFI")).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(postTelemetry(student, telemetry("v1", 99.0, "MOBILE_DATA")).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        assertThat(count("telemetry_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT network_type FROM telemetry_events", String.class))
                .as("the first answer wins").isEqualTo("HOSTEL_WIFI");
    }

    @Test
    void differentStudentsGetDifferentAnonymousIds() throws Exception {
        postTelemetry(student, telemetry("v1", 1.0, "OTHER"));
        postTelemetry(otherStudent, telemetry("v1", 2.0, "OTHER"));

        List<String> ids = jdbc.queryForList("SELECT anon_id FROM telemetry_events ORDER BY id", String.class)
                .stream().map(String::trim).toList();
        assertThat(ids).containsExactlyInAnyOrder(expectedAnonId(student), expectedAnonId(otherStudent));
    }

    @Test
    void invalidTelemetryFieldsAre400() {
        assertThat(postTelemetry(student, telemetry("v1", 1.0, "CARRIER_PIGEON")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postTelemetry(student, telemetry("v1", -1.0, "OTHER")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postTelemetry(student, new TelemetryRequest(null, "v1", 1.0, 1L, 0, 1, "OTHER")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("telemetry_events")).isZero();
    }

    @Test
    void telemetryNeedsAStudentToken() {
        assertThat(postTelemetry(teacher, telemetry("v1", 1.0, "OTHER")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postTelemetry(null, telemetry("v1", 1.0, "OTHER")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void surveyIsStoredAnonymouslyAndRepeatsAreIgnored() throws Exception {
        ResponseEntity<String> first = postSurvey(student, new SurveyRequest(assignmentId, "YES", "HOSTEL_WIFI"));
        ResponseEntity<String> second = postSurvey(student, new SurveyRequest(assignmentId, "NO", "CAMPUS_LAN"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(count("survey_responses")).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM survey_responses");
        assertThat(row.get("anon_id").toString().trim()).isEqualTo(expectedAnonId(student));
        assertThat(row.get("marked_late_before")).isEqualTo("YES");
        assertThat(row.get("usual_network")).isEqualTo("HOSTEL_WIFI");
    }

    @Test
    void invalidSurveyAnswersAndRolesAreRejected() {
        assertThat(postSurvey(student, new SurveyRequest(assignmentId, "MAYBE", "OTHER")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postSurvey(student, new SurveyRequest(assignmentId, "YES", "UNKNOWN")).getStatusCode())
                .as("UNKNOWN is a telemetry value, not a survey answer").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postSurvey(teacher, new SurveyRequest(assignmentId, "YES", "OTHER")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postSurvey(null, new SurveyRequest(assignmentId, "YES", "OTHER")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(count("survey_responses")).isZero();
    }
}
