package com.deadlinedrop.telemetry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

/** The secret salt for anonymising student ids; the app refuses to start without it. */
@ConfigurationProperties(prefix = "dd.telemetry")
@Validated
@Profile("!storage")
public record TelemetryProperties(
        @NotBlank(message = "TELEMETRY_SALT must be set")
        @Size(min = 16, message = "TELEMETRY_SALT must be at least 16 characters")
        String salt) {
}
