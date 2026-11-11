package com.deadlinedrop.api;

import com.deadlinedrop.api.dto.BaselineDtos.BaselineSubmissionResponse;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Timing;
import com.deadlinedrop.security.CurrentUser;
import com.deadlinedrop.service.AssignmentAccess;
import com.deadlinedrop.service.BaselineSubmissionService;
import com.deadlinedrop.service.DeadlinePolicy;
import com.deadlinedrop.storage.StoragePort;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Arm A baseline upload controller: file bytes stream through the application and the submission
 * timestamp is taken only after the entire byte transfer completes.
 */
@RestController
@Profile("naive")
public class NaiveSubmissionController {

    private final AssignmentAccess assignmentAccess;
    private final DeadlinePolicy deadlinePolicy;
    private final BaselineSubmissionService baselineSubmissionService;
    private final StoragePort storagePort;
    private final Clock clock;

    public NaiveSubmissionController(
            AssignmentAccess assignmentAccess,
            DeadlinePolicy deadlinePolicy,
            BaselineSubmissionService baselineSubmissionService,
            StoragePort storagePort,
            Clock clock) {
        this.assignmentAccess = Objects.requireNonNull(assignmentAccess, "assignmentAccess must not be null");
        this.deadlinePolicy = Objects.requireNonNull(deadlinePolicy, "deadlinePolicy must not be null");
        this.baselineSubmissionService = Objects.requireNonNull(baselineSubmissionService, "baselineSubmissionService must not be null");
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @PostMapping(value = "/api/assignments/{assignmentId}/naive-submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<BaselineSubmissionResponse> submit(
            @PathVariable UUID assignmentId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
            Authentication authentication) {
        UUID studentId = CurrentUser.id(authentication);
        Assignment assignment = assignmentAccess.loadForStudent(assignmentId, studentId);

        String contentType = file.getContentType();
        if (!assignment.allows(contentType)) {
            throw ApiException.unsupportedType(ErrorCodes.TYPE_NOT_ALLOWED, "File type not allowed: " + contentType);
        }

        if (file.getSize() > assignment.getMaxBytes()) {
            throw ApiException.payloadTooLarge(
                    ErrorCodes.FILE_TOO_LARGE,
                    "File size " + file.getSize() + " exceeds max allowed bytes of " + assignment.getMaxBytes()
            );
        }

        UUID newId = UUID.randomUUID();
        String objectPath = "a/" + assignmentId + "/s/" + studentId + "/" + newId;

        try (InputStream in = file.getInputStream()) {
            storagePort.put(objectPath, contentType, in, file.getSize());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read upload stream", e);
        }

        // stamping after the bytes have arrived is exactly what makes this baseline unfair to slow uploads
        Instant stamp = clock.instant();

        Timing timing = deadlinePolicy.timing(stamp, assignment);

        baselineSubmissionService.insert(
                newId,
                assignmentId,
                studentId,
                idempotencyKey,
                objectPath,
                file.getSize(),
                stamp,
                "UPLOAD_COMPLETE",
                timing
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new BaselineSubmissionResponse(newId, stamp, "UPLOAD_COMPLETE", timing.name()));
    }
}
