package com.deadlinedrop.security;

import com.deadlinedrop.api.dto.AuthDtos.TokenResponse;
import com.deadlinedrop.domain.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

@Service
@Profile("!storage")
public class JwtService {

    private static final String DEFAULT_TTL = "PT12H";
    private static final String ISSUER = "deadlinedrop";

    private final Clock clock;
    private final Duration ttl;
    private final SecretKey secretKey;
    private final NimbusJwtEncoder jwtEncoder;

    public JwtService(
            Clock clock,
            @Value("${JWT_SECRET:${jwt.secret:}}") String base64Secret,
            @Value("${dd.jwt.ttl:" + DEFAULT_TTL + "}") Duration ttl) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ttl = ttl != null ? ttl : Duration.parse(DEFAULT_TTL);

        if (base64Secret == null || base64Secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET environment variable is missing or blank");
        }

        byte[] secretBytes;
        try {
            secretBytes = Base64.getDecoder().decode(base64Secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT_SECRET environment variable is not valid base64", e);
        }

        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must decode to at least 32 bytes (256 bits) for HS256, but decoded to "
                            + secretBytes.length + " bytes"
            );
        }

        this.secretKey = new SecretKeySpec(secretBytes, "HmacSHA256");
        this.jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretBytes));
    }

    public TokenResponse issue(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(this.ttl);
        return issue(user, now, expiresAt);
    }

    public TokenResponse issue(User user, Instant issuedAt, Instant expiresAt) {
        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.getId().toString())
                .claim("role", user.getRole().name())
                .claim("name", user.getDisplayName())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
        return new TokenResponse(token, expiresAt, user.getRole().name(), user.getDisplayName());
    }

    public SecretKey getSecretKey() {
        return secretKey;
    }
}
