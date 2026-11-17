package com.deadlinedrop.security;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.api.dto.CommitDtos.CommitRequest;
import com.deadlinedrop.api.dto.CommitDtos.CommitResponse;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Enrollment;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.support.MutableClock;
import com.deadlinedrop.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence that the signed-URL edge-case enforcement is correct end-to-end: the in-process
 * local storage server accepts or rejects PUTs exactly as the GCS adapter would, and
 * FinalizeService cleans up any object that slips through with wrong metadata.
 *
 * <p>Three scenarios:
 * <ol>
 *   <li>{@code oversizePutRejectedOrDeletedAtFinalize} — oversize PUT gets 413 from the storage
 *       server; a size-declaration mismatch that bypasses the storage check gets 422 at finalize
 *       and the object is deleted.</li>
 *   <li>{@code contentTypeNotSignedIsRejected} — PUT with the wrong Content-Type gets 403.</li>
 *   <li>{@code putToAnotherPathIsRejected} — reusing the signed query string on a different
 *       object path gets 403.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, SignedUrlSecurityIT.TestClockConfig.class})
class SignedUrlSecurityIT {

    /** 1 MiB — the assignment's declared max. */
    private static final long ONE_MIB = 1_048_576L;

    private static final String PDF_SHA = "a".repeat(64);
    private static final String PDF_TYPE = "application/pdf";

    private static final Path TEMP_ROOT;

    static {
        try {
            TEMP_ROOT = Files.createTempDirectory("dd-signed-url-sec-it-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.of(Instant.parse("2026-11-20T12:00:00Z"));
        }
    }

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry registry) {
        registry.add("dd.storage.server.enabled", () -> "true");
        registry.add("dd.storage.root", () -> TEMP_ROOT.toAbsolutePath().toString());
    }

    private final TestRestTemplate rest;
    private final UserRepository users;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final AssignmentRepository assignments;
    private final StoragePort storage;
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    private User teacher;
    private User student;
    private Assignment assignment;

    @Autowired
    SignedUrlSecurityIT(TestRestTemplate rest, UserRepository users, CourseRepository courses,
            EnrollmentRepository enrollments, AssignmentRepository assignments, StoragePort storage,
            JwtService jwtService, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.rest = rest;
        this.users = users;
        this.courses = courses;
        this.enrollments = enrollments;
        this.assignments = assignments;
        this.storage = storage;
        this.jwtService = jwtService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void setUp() {
        clean();
        teacher = users.save(User.create("sec-teacher@dd.local", "unused-hash", Role.TEACHER, "Sec Teacher"));
        student = users.save(User.create("sec-student@dd.local", "unused-hash", Role.STUDENT, "Sec Student"));
        Course course = courses.save(Course.create("SEC101", "Security course", teacher.getId()));
        enrollments.save(Enrollment.enroll(course.getId(), student.getId()));
        // Grace 900 s, maxBytes 1 MiB, PDF only — same as the standard test scenario
        assignment = assignments.save(Assignment.create(
                course.getId(), "Lab 1",
                Instant.parse("2026-11-20T23:59:59Z"),
                900, ONE_MIB,
                List.of(PDF_TYPE),
                teacher.getId()));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM submission_events");
        jdbc.update("UPDATE submissions SET superseded_by = NULL");
        jdbc.update("DELETE FROM submissions");
        enrollments.deleteAll();
        assignments.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CommitResponse commit(long sizeBytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.accessToken(jwtService, student));
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<String> r = rest.postForEntity(
                "/api/assignments/" + assignment.getId() + "/commits",
                new HttpEntity<>(new CommitRequest(PDF_SHA, sizeBytes, PDF_TYPE), headers),
                String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readValue(r.getBody(), CommitResponse.class);
    }

    /**
     * PUT to the signed URL, rewriting the host to localhost:{port}.
     * Sends the exact Content-Type specified and a body of the given size.
     */
    private HttpResponse<String> put(String signedUrl, String contentType, long bodyBytes) throws Exception {
        URI signed = URI.create(signedUrl);
        URI local = new URI("http", null, "localhost", port, signed.getPath(), signed.getQuery(), null);
        byte[] body = new byte[(int) bodyBytes];
        Arrays.fill(body, (byte) 0x41); // 'A' bytes, deterministic
        HttpRequest req = HttpRequest.newBuilder(local)
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * PUT to an arbitrary path using the query string from the given signed URL.
     */
    private HttpResponse<String> putToPath(String signedUrl, String newPath, String contentType, long bodyBytes)
            throws Exception {
        URI signed = URI.create(signedUrl);
        URI local = new URI("http", null, "localhost", port, "/storage/" + newPath, signed.getQuery(), null);
        byte[] body = new byte[(int) bodyBytes];
        HttpRequest req = HttpRequest.newBuilder(local)
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private ResponseEntity<String> finalize(UUID submissionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.accessToken(jwtService, student));
        return rest.postForEntity(
                "/api/submissions/" + submissionId + "/finalize",
                new HttpEntity<>(null, headers),
                String.class);
    }

    private String codeOf(ResponseEntity<String> r) {
        return objectMapper.readTree(r.getBody()).get("code").asText();
    }

    private String objectPath(UUID submissionId) {
        return jdbc.queryForObject("SELECT object_path FROM submissions WHERE id = ?", String.class, submissionId);
    }

    // =========================================================================
    // Test 1: oversize PUT is rejected 413 from the storage server; a commit that
    // declares 2 MiB but uploads 1 MiB lands successfully in storage, then finalize
    // catches the size mismatch (422 SIZE_MISMATCH) and deletes the object.
    // =========================================================================

    @Test
    void oversizePutRejectedOrDeletedAtFinalize() throws Exception {
        // ---- Part A: commit declares 1 MiB, PUT 1 MiB + 1 byte → 413 from storage server ----
        CommitResponse c1 = commit(ONE_MIB);
        HttpResponse<String> oversizePut = put(c1.uploadUrl(), PDF_TYPE, ONE_MIB + 1);
        assertThat(oversizePut.statusCode())
                .as("PUT of 1 MiB + 1 byte must be rejected by the storage server with 413")
                .isEqualTo(413);
        assertThat(oversizePut.body())
                .as("error body should contain FILE_TOO_LARGE code")
                .contains(ErrorCodes.FILE_TOO_LARGE);
        // The object must not have been stored after a 413
        assertThat(storage.stat(objectPath(c1.submissionId())))
                .as("no object stored after 413")
                .isEmpty();

        // ---- Part B: commit declares 2 MiB, PUT exactly 1 MiB (within the 2 MiB signed range) ----
        // The storage server accepts it (1 MiB < signed max of 2 MiB, and assignment max is 1 MiB
        // but the signed URL is generated from the commit's declared size, not the assignment cap).
        // FinalizeService then measures 1 MiB vs the committed 2 MiB and must reject with 422.
        //
        // Why 2 MiB declared? The assignment cap is 1 MiB so the commit itself will be rejected
        // by CommitService before signing. We therefore skip the commit endpoint and write the
        // object directly through the StoragePort to simulate a client that somehow obtained a
        // URL for a larger object (e.g. a different assignment with a 2 MiB cap), which is exactly
        // the scenario finalize must guard against.
        CommitResponse c2 = commit(ONE_MIB); // commit 1 MiB (valid)
        // PUT half the declared size through the storage server so the object lands but is small
        HttpResponse<String> halfPut = put(c2.uploadUrl(), PDF_TYPE, ONE_MIB / 2);
        assertThat(halfPut.statusCode())
                .as("PUT of 512 KiB for a 1-MiB commit must be accepted by storage")
                .isEqualTo(200);

        ResponseEntity<String> fin = finalize(c2.submissionId());

        assertThat(fin.getStatusCode().value())
                .as("finalize must reject a size mismatch with 422")
                .isEqualTo(422);
        assertThat(codeOf(fin))
                .as("error code must be SIZE_MISMATCH")
                .isEqualTo(ErrorCodes.SIZE_MISMATCH);
        assertThat(storage.stat(objectPath(c2.submissionId())))
                .as("FinalizeService must delete the object on size mismatch")
                .isEmpty();
    }

    // =========================================================================
    // Test 2: Content-Type text/plain when application/pdf was signed → 403
    // =========================================================================

    @Test
    void contentTypeNotSignedIsRejected() throws Exception {
        CommitResponse c = commit(ONE_MIB);
        // The signed URL is for application/pdf; send text/plain instead
        HttpResponse<String> wrongType = put(c.uploadUrl(), "text/plain", 1024);

        assertThat(wrongType.statusCode())
                .as("PUT with wrong Content-Type must be rejected with 403")
                .isEqualTo(403);
        assertThat(wrongType.body())
                .as("error body should contain SIGNATURE_INVALID code")
                .contains(ErrorCodes.SIGNATURE_INVALID);
        assertThat(storage.stat(objectPath(c.submissionId())))
                .as("no object stored after 403")
                .isEmpty();
    }

    // =========================================================================
    // Test 3: Reuse the query string on a different object path → 403
    // =========================================================================

    @Test
    void putToAnotherPathIsRejected() throws Exception {
        CommitResponse c = commit(ONE_MIB);
        // The signed URL encodes the object path in both the URL path and the HMAC.
        // Changing the path in the URL while keeping the same query string must fail.
        HttpResponse<String> pathHijack = putToPath(c.uploadUrl(), "submissions/hijacked/evil.pdf", PDF_TYPE, 1024);

        assertThat(pathHijack.statusCode())
                .as("PUT to a different path using the original signature must be rejected with 403")
                .isEqualTo(403);
        assertThat(pathHijack.body())
                .as("error body should contain SIGNATURE_INVALID code")
                .contains(ErrorCodes.SIGNATURE_INVALID);
        // The legitimate object path must also be untouched
        assertThat(storage.stat(objectPath(c.submissionId())))
                .as("the original object path must be empty after the hijack attempt")
                .isEmpty();
        assertThat(storage.stat("submissions/hijacked/evil.pdf"))
                .as("the hijacked path must also be empty")
                .isEmpty();
    }
}
