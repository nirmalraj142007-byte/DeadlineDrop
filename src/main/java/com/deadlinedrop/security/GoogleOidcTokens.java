package com.deadlinedrop.security;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Decoder and validators for the Google-signed OIDC tokens Cloud Scheduler sends to
 * {@code /internal/**}. Kept out of the bean factory on purpose: the main chain already has a
 * {@code JwtDecoder} bean (HS256 for students and teachers), and a second one would make every
 * by-type lookup ambiguous.
 */
final class GoogleOidcTokens {

    static final String ISSUER = "https://accounts.google.com";

    /** Discovery document and JWKS fetches from Google: fail within a few seconds, never hang a request. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private GoogleOidcTokens() {
    }

    static NimbusJwtDecoder decoder(InternalOidcProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(ISSUER)
                .restOperations(new RestTemplate(requestFactory))
                .build();
        decoder.setJwtValidator(validator(properties.getAudience(), properties.getSchedulerEmail()));
        return decoder;
    }

    /** Expiry and issuer, then: aud contains the service URL, the caller is the scheduler's account, Google verified it. */
    static OAuth2TokenValidator<Jwt> validator(String audience, String schedulerEmail) {
        Objects.requireNonNull(audience, "audience must not be null");
        Objects.requireNonNull(schedulerEmail, "schedulerEmail must not be null");
        List<OAuth2TokenValidator<Jwt>> validators = List.of(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new JwtClaimValidator<Collection<String>>("aud", aud -> aud != null && aud.contains(audience)),
                new JwtClaimValidator<String>("email", schedulerEmail::equals),
                new JwtClaimValidator<Object>("email_verified",
                        verified -> Boolean.TRUE.equals(verified) || "true".equals(verified)));
        return new DelegatingOAuth2TokenValidator<>(validators);
    }
}
