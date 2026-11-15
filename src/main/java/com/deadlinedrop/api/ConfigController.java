package com.deadlinedrop.api;

import com.deadlinedrop.api.dto.ConfigResponse;
import com.deadlinedrop.security.PublicEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Objects;

/** What the front end needs to know about this deployment before login. */
@RestController
@Profile("!storage")
public class ConfigController {

    static final long MAX_BYTES_CAP = 26_214_400L;
    static final String CONSENT_VERSION = "v1";

    private final String arm;
    private final String armLabel;
    private final Clock clock;

    public ConfigController(Environment environment, Clock clock) {
        Objects.requireNonNull(environment, "environment must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (environment.acceptsProfiles(Profiles.of("naive"))) {
            this.arm = "A";
            this.armLabel = "Arm A: stamped when the upload finishes";
        } else if (environment.acceptsProfiles(Profiles.of("direct-finalize"))) {
            this.arm = "B";
            this.armLabel = "Arm B: stamped when the upload finishes";
        } else {
            this.arm = "C";
            this.armLabel = "DeadlineDrop: time locked when you commit";
        }
    }

    @Operation(summary = "Deployment settings: arm, label, upload size cap, consent version, server time")
    @PublicEndpoint
    @GetMapping("/api/config")
    public ResponseEntity<ConfigResponse> config() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ConfigResponse(arm, armLabel, MAX_BYTES_CAP, CONSENT_VERSION, clock.millis()));
    }
}
