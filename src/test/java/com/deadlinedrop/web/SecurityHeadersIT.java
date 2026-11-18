package com.deadlinedrop.web;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.support.HttpCalls;
import com.deadlinedrop.support.HttpCalls.Reply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every response carries the same hardening headers, whether it is a page, an API answer or a
 * 401, and the app allows no cross-origin calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SecurityHeadersIT {

    private static final String EXPECTED_CSP = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; connect-src 'self' https://storage.googleapis.com http://localhost:9000; "
            + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    @LocalServerPort
    private int port;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/", "/css/app.css", "/api/time", "/api/config", "/api/me", "/api/does-not-exist"})
    void everyResponseCarriesTheHardeningHeaders(String path) {
        Reply reply = new HttpCalls(port).call("GET", path, null, null);

        assertThat(reply.header("Content-Security-Policy")).contains(EXPECTED_CSP);
        assertThat(reply.header("X-Content-Type-Options")).contains("nosniff");
        assertThat(reply.header("Referrer-Policy")).contains("no-referrer");
        assertThat(reply.header("Permissions-Policy")).contains("camera=(), microphone=(), geolocation=()");
    }

    @Test
    void hstsIsOnlySentUnderTheCloudProfile() {
        Reply reply = new HttpCalls(port).call("GET", "/", null, null);

        assertThat(reply.header("Strict-Transport-Security")).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/time", "/api/config", "/api/me", "/api/courses", "/"})
    void aCrossOriginRequestGetsNoAllowOriginHeader(String path) {
        Reply reply = new HttpCalls(port).call("GET", path, null, Map.of("Origin", "https://evil.example"), null);

        assertThat(reply.headers()).doesNotContainKey("access-control-allow-origin");
        assertThat(reply.headers()).doesNotContainKey("access-control-allow-credentials");
    }

    @Test
    void aCrossOriginPreflightIsNotApproved() {
        Reply reply = new HttpCalls(port).call("OPTIONS", "/api/courses", null, Map.of(
                "Origin", "https://evil.example",
                "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "authorization,content-type"), null);

        assertThat(reply.status()).isNotEqualTo(200);
        assertThat(reply.headers()).doesNotContainKey("access-control-allow-origin");
        assertThat(reply.headers()).doesNotContainKey("access-control-allow-methods");
    }
}
