package com.deadlinedrop.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

/**
 * Anonymised pilot telemetry and survey bodies. Neither carries a student id; the server derives
 * the anonymous id from the caller's token.
 */
public final class TelemetryDtos {

    private TelemetryDtos() {
    }

    public record TelemetryRequest(
            @NotNull UUID assignmentId,
            String consentVersion,
            @PositiveOrZero Double uploadMbps,
            @PositiveOrZero Long sizeBytes,
            Integer clickOffsetMs,
            @PositiveOrZero Integer hashMs,
            @Pattern(regexp = "^(HOSTEL_WIFI|MOBILE_DATA|HOME_BROADBAND|CAMPUS_LAN|OTHER|UNKNOWN)$") String networkType) {
    }

    public record SurveyRequest(
            @NotNull UUID assignmentId,
            @Pattern(regexp = "^(YES|NO|UNSURE)$") String markedLateBefore,
            @Pattern(regexp = "^(HOSTEL_WIFI|MOBILE_DATA|HOME_BROADBAND|CAMPUS_LAN|OTHER)$") String usualNetwork) {
    }
}
