package com.deadlinedrop.telemetry;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.api.dto.TelemetryDtos.SurveyRequest;
import com.deadlinedrop.api.dto.TelemetryDtos.TelemetryRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Stores pilot telemetry and survey answers under an anonymous id: the hex HMAC-SHA256 of the
 * student id with a secret salt. The tables have no foreign key to users, and a second post for the
 * same assignment is ignored, so retries are harmless.
 */
@Service
@Profile("!storage")
public class TelemetryService {

    private static final String HMAC = "HmacSHA256";

    private final JdbcTemplate jdbc;
    private final SecretKeySpec key;

    public TelemetryService(JdbcTemplate jdbc, TelemetryProperties properties) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        this.key = new SecretKeySpec(properties.salt().getBytes(StandardCharsets.UTF_8), HMAC);
    }

    public void record(UUID studentId, TelemetryRequest r) {
        if (r.consentVersion() == null || r.consentVersion().isBlank()) {
            throw ApiException.badRequest(ErrorCodes.CONSENT_REQUIRED,
                    "Telemetry is only recorded after the student has seen and accepted the consent text");
        }
        jdbc.update("""
                INSERT INTO telemetry_events
                    (anon_id, assignment_id, consent_version, upload_mbps, size_bytes, click_offset_ms, hash_ms, network_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (anon_id, assignment_id) DO NOTHING
                """,
                anonId(studentId), r.assignmentId(), r.consentVersion(), r.uploadMbps(), r.sizeBytes(),
                r.clickOffsetMs(), r.hashMs(), r.networkType());
    }

    public void recordSurvey(UUID studentId, SurveyRequest r) {
        jdbc.update("""
                INSERT INTO survey_responses (anon_id, assignment_id, marked_late_before, usual_network)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (anon_id, assignment_id) DO NOTHING
                """,
                anonId(studentId), r.assignmentId(), r.markedLateBefore(), r.usualNetwork());
    }

    String anonId(UUID studentId) {
        Objects.requireNonNull(studentId, "studentId must not be null");
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(studentId.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by the JDK specification", e);
        }
    }
}
