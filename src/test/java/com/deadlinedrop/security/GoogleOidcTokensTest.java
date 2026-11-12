package com.deadlinedrop.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleOidcTokensTest {

    private static final String AUDIENCE = "https://dd-arm-c-example.a.run.app";
    private static final String SCHEDULER = "dd-scheduler@example-project.iam.gserviceaccount.com";

    private final OAuth2TokenValidator<Jwt> validator = GoogleOidcTokens.validator(AUDIENCE, SCHEDULER);

    private static Jwt token(Consumer<Jwt.Builder> change) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuer(GoogleOidcTokens.ISSUER)
                .audience(List.of(AUDIENCE))
                .claim("email", SCHEDULER)
                .claim("email_verified", true)
                .issuedAt(now.minusSeconds(10))
                .expiresAt(now.plusSeconds(3600));
        change.accept(builder);
        return builder.build();
    }

    @Test
    void schedulerTokenForThisServiceIsAccepted() {
        assertThat(validator.validate(token(b -> { })).hasErrors()).isFalse();
    }

    @Test
    void tokenForAnotherAudienceIsRejected() {
        assertThat(validator.validate(token(b -> b.audience(List.of("https://dd-arm-b-example.a.run.app"))))
                .hasErrors()).isTrue();
    }

    @Test
    void tokenForAnotherServiceAccountIsRejected() {
        assertThat(validator.validate(token(b -> b.claim("email", "someone@example-project.iam.gserviceaccount.com")))
                .hasErrors()).isTrue();
    }

    @Test
    void tokenWithoutEmailIsRejected() {
        assertThat(validator.validate(token(b -> b.claims(c -> c.remove("email")))).hasErrors()).isTrue();
    }

    @Test
    void unverifiedEmailIsRejected() {
        assertThat(validator.validate(token(b -> b.claim("email_verified", false))).hasErrors()).isTrue();
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        assertThat(validator.validate(token(b -> b.issuer("https://issuer.example.com"))).hasErrors()).isTrue();
    }

    @Test
    void expiredTokenIsRejected() {
        Instant past = Instant.now().minusSeconds(7200);
        assertThat(validator.validate(token(b -> b.issuedAt(past).expiresAt(past.plusSeconds(600))))
                .hasErrors()).isTrue();
    }
}
