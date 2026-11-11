package com.deadlinedrop.api;

import com.deadlinedrop.api.dto.CommitDtos.CommitRequest;
import com.deadlinedrop.api.dto.CommitDtos.CommitResponse;
import com.deadlinedrop.security.CurrentUser;
import com.deadlinedrop.service.CommitService;
import com.deadlinedrop.service.CommitService.CommitCommand;
import com.deadlinedrop.service.CommitService.CommitResult;
import com.deadlinedrop.storage.StoragePort.SignedUrl;
import com.deadlinedrop.web.CommitTimestampFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Arm C commit endpoint. The body is a few hundred bytes (hash, size, type); the file bytes never
 * pass through this application.
 */
@RestController
@Profile("!naive & !direct-finalize & !storage")
public class CommitController {

    private static final Logger log = LoggerFactory.getLogger(CommitController.class);

    private final CommitService commitService;
    private final Clock clock;

    public CommitController(CommitService commitService, Clock clock) {
        this.commitService = Objects.requireNonNull(commitService, "commitService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @PostMapping("/api/assignments/{assignmentId}/commits")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<CommitResponse> commit(
            @PathVariable UUID assignmentId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CommitRequest body,
            HttpServletRequest request,
            Authentication authentication) {
        Instant arrivedAt;
        if (request.getAttribute(CommitTimestampFilter.ARRIVED_AT) instanceof Instant stamped) {
            arrivedAt = stamped;
        } else {
            log.warn("Commit request reached the controller without an arrival stamp; stamping now");
            arrivedAt = clock.instant();
        }

        CommitResult result = commitService.commit(new CommitCommand(
                CurrentUser.id(authentication), assignmentId, idempotencyKey,
                body.sha256(), body.sizeBytes(), body.contentType(), arrivedAt));

        SignedUrl signed = result.signedUrl();
        CommitResponse response = new CommitResponse(
                result.submissionId(),
                result.committedAt(),
                result.timing().name(),
                result.status().name(),
                signed == null ? null : signed.url(),
                signed == null ? null : signed.headers(),
                signed == null ? null : signed.expiresAt());

        long appMs = Math.max(0, Duration.between(arrivedAt, clock.instant()).toMillis());
        String serverTiming = "app;dur=" + appMs + ", db;dur=" + result.dbMs() + ", sign;dur=" + result.signMs();

        return ResponseEntity
                .status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Server-Timing", serverTiming)
                .body(response);
    }
}
