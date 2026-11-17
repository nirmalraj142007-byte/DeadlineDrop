package com.deadlinedrop.service;

import com.deadlinedrop.config.ReceiptProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Signed commit receipts. When the database cannot be reached at commit time, the server's arrival
 * stamp and the hash it binds to are written into a token signed with HMAC-SHA256 instead of a row.
 * The browser uploads as usual and hands the token back at finalize; only this server can have
 * produced it, so the stamp inside is as trustworthy as one read from the database.
 *
 * <p>Token: {@code base64url(payload) + "." + base64url(HMAC-SHA256(key, payload))}, payload
 * {@code v1|submissionId|studentId|assignmentId|sha256|sizeBytes|contentType|committedAtEpochMs|nonce}.
 */
@Service
@Profile("!storage")
public class ReceiptService {

    static final String VERSION = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;
    /** Fields before contentType and after it; contentType is whatever lies between, so a '|' in it cannot shift fields. */
    private static final int LEADING_FIELDS = 6;
    private static final int TRAILING_FIELDS = 2;

    /** What a receipt proves: this student committed these bytes for this assignment at {@code committedAt}. */
    public record Receipt(
            UUID submissionId,
            UUID studentId,
            UUID assignmentId,
            String sha256,
            long sizeBytes,
            String contentType,
            Instant committedAt,
            UUID nonce) {

        public Receipt {
            Objects.requireNonNull(submissionId, "submissionId must not be null");
            Objects.requireNonNull(studentId, "studentId must not be null");
            Objects.requireNonNull(assignmentId, "assignmentId must not be null");
            Objects.requireNonNull(sha256, "sha256 must not be null");
            Objects.requireNonNull(contentType, "contentType must not be null");
            Objects.requireNonNull(committedAt, "committedAt must not be null");
            Objects.requireNonNull(nonce, "nonce must not be null");
        }
    }

    private final SecretKeySpec key;
    private final boolean enabled;

    public ReceiptService(ReceiptProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(properties.hmacKey().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("RECEIPT_HMAC_KEY must be base64 (generate one with: openssl rand -base64 32)", e);
        }
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("RECEIPT_HMAC_KEY must decode to at least " + MIN_KEY_BYTES
                    + " bytes, but decoded to " + keyBytes.length + " bytes");
        }
        this.key = new SecretKeySpec(keyBytes, HMAC_ALGORITHM);
        Arrays.fill(keyBytes, (byte) 0);
        this.enabled = properties.enabled();
        // Fail at startup, not at 11:59, if the JDK cannot use the key.
        mac();
    }

    /** Whether a commit that cannot reach the database is answered with a receipt (true) or a 503 (false). */
    public boolean enabled() {
        return enabled;
    }

    public String issue(Receipt r) {
        Objects.requireNonNull(r, "receipt must not be null");
        String payload = String.join("|",
                VERSION,
                r.submissionId().toString(),
                r.studentId().toString(),
                r.assignmentId().toString(),
                r.sha256(),
                Long.toString(r.sizeBytes()),
                r.contentType(),
                Long.toString(r.committedAt().toEpochMilli()),
                r.nonce().toString());
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString(payloadBytes) + "." + b64.encodeToString(mac().doFinal(payloadBytes));
    }

    /**
     * Checks the signature in constant time and parses the payload. Empty for anything this
     * server did not sign: a malformed token, a wrong signature, or an unknown version.
     */
    public Optional<Receipt> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.') || dot == token.length() - 1) {
            return Optional.empty();
        }
        byte[] payloadBytes;
        byte[] signature;
        try {
            Base64.Decoder b64 = Base64.getUrlDecoder();
            payloadBytes = b64.decode(token.substring(0, dot));
            signature = b64.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(mac().doFinal(payloadBytes), signature)) {
            return Optional.empty();
        }
        return parse(new String(payloadBytes, StandardCharsets.UTF_8));
    }

    private static Optional<Receipt> parse(String payload) {
        String[] f = payload.split("\\|", -1);
        if (f.length < LEADING_FIELDS + 1 + TRAILING_FIELDS || !VERSION.equals(f[0])) {
            return Optional.empty();
        }
        int t = f.length - TRAILING_FIELDS;
        String contentType = String.join("|", Arrays.copyOfRange(f, LEADING_FIELDS, t));
        try {
            return Optional.of(new Receipt(
                    UUID.fromString(f[1]),
                    UUID.fromString(f[2]),
                    UUID.fromString(f[3]),
                    f[4],
                    Long.parseLong(f[5]),
                    contentType,
                    Instant.ofEpochMilli(Long.parseLong(f[t])),
                    UUID.fromString(f[t + 1])));
        } catch (IllegalArgumentException e) {
            // Every payload issue() signs parses; a signed one that does not is treated as invalid, never guessed at.
            return Optional.empty();
        }
    }

    private Mac mac() {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is required by the JDK specification", e);
        }
    }
}
