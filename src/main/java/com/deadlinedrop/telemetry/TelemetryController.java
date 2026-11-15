package com.deadlinedrop.telemetry;

import com.deadlinedrop.api.dto.TelemetryDtos.SurveyRequest;
import com.deadlinedrop.api.dto.TelemetryDtos.TelemetryRequest;
import com.deadlinedrop.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@Profile("!storage")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = Objects.requireNonNull(telemetryService, "telemetryService must not be null");
    }

    @Operation(summary = "Record anonymised upload telemetry for one assignment (needs consent)")
    @PostMapping("/api/telemetry")
    @PreAuthorize("hasRole('STUDENT')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void telemetry(@Valid @RequestBody TelemetryRequest body, Authentication authentication) {
        telemetryService.record(CurrentUser.id(authentication), body);
    }

    @Operation(summary = "Record an anonymised pilot survey answer for one assignment")
    @PostMapping("/api/survey")
    @PreAuthorize("hasRole('STUDENT')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void survey(@Valid @RequestBody SurveyRequest body, Authentication authentication) {
        telemetryService.recordSurvey(CurrentUser.id(authentication), body);
    }
}
