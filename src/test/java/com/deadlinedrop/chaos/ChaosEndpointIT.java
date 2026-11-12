package com.deadlinedrop.chaos;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.security.JwtService;
import com.deadlinedrop.support.TestTokens;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure-injection switch: absent unless {@code dd.chaos.enabled=true}, admin-only when present.
 * Two separate application contexts, one per flag value. The enabled one swaps the real
 * {@link RuntimeHalter} for a counter, so the test JVM survives the halt.
 */
class ChaosEndpointIT {

    /** Users and tokens for one test, removed afterwards: the flag-off context is shared with other ITs. */
    private record Callers(UserRepository users, JwtService jwtService, TestRestTemplate rest, List<User> created) {

        Callers(UserRepository users, JwtService jwtService, TestRestTemplate rest) {
            this(users, jwtService, rest, new ArrayList<>());
        }

        String tokenFor(Role role) {
            User user = users.save(User.create(
                    role.name().toLowerCase() + "-" + UUID.randomUUID() + "@dd.local",
                    "{noop}not-used-by-token-auth", role, "Chaos " + role.name()));
            created.add(user);
            return TestTokens.accessToken(jwtService, user);
        }

        ResponseEntity<String> call(HttpMethod method, String path, String token) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            return rest.exchange(path, method, new HttpEntity<>(headers), String.class);
        }

        void cleanUp() {
            users.deleteAll(created);
        }
    }

    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureTestRestTemplate
    @ActiveProfiles("test")
    @Import(TestcontainersConfiguration.class)
    class FlagOff {

        @Test
        void haltAndStatusRoutesDoNotExist(
                @Autowired UserRepository users, @Autowired JwtService jwtService, @Autowired TestRestTemplate rest) {
            Callers callers = new Callers(users, jwtService, rest);
            try {
                String admin = callers.tokenFor(Role.ADMIN);

                assertThat(callers.call(HttpMethod.POST, "/admin/chaos/halt", admin).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(callers.call(HttpMethod.GET, "/admin/chaos/status", admin).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND);
            } finally {
                callers.cleanUp();
            }
        }
    }

    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureTestRestTemplate
    @ActiveProfiles("test")
    @Import({TestcontainersConfiguration.class, FlagOn.CountingHalterConfig.class})
    @TestPropertySource(properties = "dd.chaos.enabled=true")
    class FlagOn {

        @TestConfiguration(proxyBeanMethods = false)
        static class CountingHalterConfig {
            @Bean
            @Primary
            CountingHalter countingHalter() {
                return new CountingHalter();
            }
        }

        static final class CountingHalter implements Halter {
            final AtomicInteger calls = new AtomicInteger();

            @Override
            public void halt() {
                calls.incrementAndGet();
            }
        }

        @Test
        void teacherIsForbiddenAdminHaltsOnce(
                @Autowired UserRepository users, @Autowired JwtService jwtService, @Autowired TestRestTemplate rest,
                @Autowired CountingHalter halter, @Autowired ObjectMapper json) {
            Callers callers = new Callers(users, jwtService, rest);
            try {
                String teacher = callers.tokenFor(Role.TEACHER);
                String admin = callers.tokenFor(Role.ADMIN);
                int before = halter.calls.get();

                ResponseEntity<String> denied = callers.call(HttpMethod.POST, "/admin/chaos/halt", teacher);
                assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(halter.calls.get()).isEqualTo(before);

                ResponseEntity<String> accepted = callers.call(HttpMethod.POST, "/admin/chaos/halt", admin);
                assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                assertThat(json.readTree(accepted.getBody()).get("halting").asString()).isNotBlank();
                assertThat(halter.calls.get() - before).isEqualTo(1);

                ResponseEntity<String> status = callers.call(HttpMethod.GET, "/admin/chaos/status", admin);
                assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(json.readTree(status.getBody()).get("enabled").asBoolean()).isTrue();
            } finally {
                callers.cleanUp();
            }
        }
    }
}
