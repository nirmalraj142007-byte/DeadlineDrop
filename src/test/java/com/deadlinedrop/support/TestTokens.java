package com.deadlinedrop.support;

import com.deadlinedrop.domain.User;
import com.deadlinedrop.security.JwtService;

import java.time.Duration;
import java.time.Instant;

/**
 * Access tokens for integration tests, signed with wall-clock times.
 *
 * <p>Tests that swap in a {@link MutableClock} simulate a day near the deadline, but the resource
 * server checks a token's expiry against the real system clock. A token issued from the simulated
 * time would stop working once real time passes simulated time + TTL, so tests sign with
 * {@link Instant#now()} while the application's deadline logic keeps using the MutableClock.
 */
public final class TestTokens {

    private static final Duration TTL = Duration.ofHours(1);

    private TestTokens() {
    }

    public static String accessToken(JwtService jwtService, User user) {
        Instant issued = Instant.now();
        return jwtService.issue(user, issued, issued.plus(TTL)).accessToken();
    }
}
