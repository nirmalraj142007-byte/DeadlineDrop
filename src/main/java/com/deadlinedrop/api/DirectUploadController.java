package com.deadlinedrop.api;

import com.deadlinedrop.api.dto.BaselineDtos.BaselineSubmissionResponse;
import com.deadlinedrop.api.dto.BaselineDtos.DirectUploadRequest;
import com.deadlinedrop.api.dto.BaselineDtos.DirectUploadResponse;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.BaselineSubmissionRow;
import com.deadlinedrop.domain.Timing;
import com.deadlinedrop.security.CurrentUser;
import com.deadlinedrop.service.AssignmentAccess;
import com.deadlinedrop.service.BaselineSubmissionService;
import com.deadlinedrop.service.DeadlinePolicy;
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.storage.StoragePort.ObjectInfo;
import com.deadlinedrop.storage.StoragePort.SignedUrl;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Arm B: what a competent engineer builds today. The browser uploads straight to storage through a
 * signed URL and then calls finalize, which stamps the time. It differs from arm C only in WHEN the
 * stamp is taken: here after the bytes have landed, in arm C when the commit request arrives.
 */
@RestController
@Profile("direct-finalize")
public class DirectUploadController {

    private static final Duration URL_TTL = Duration.ofMinutes(15);
    private static final String STAMP_SOURCE = "FINALIZE";

    private final AssignmentAccess assignmentAccess;
    private final DeadlinePolicy deadlinePolicy;
    private final BaselineSubmissionService baselineSubmissionService;
    private final StoragePort storagePort;
    private final Clock clock;

    public DirectUploadController(
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

    @PostMapping("/api/assignments/{assignmentId}/direct-uploads")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<DirectUploadResponse> createUpload(
            @PathVariable UUID assignmentId,
            @Valid @RequestBody DirectUploadRequest body,
            Authentication authentication) {
        UUID studentId = CurrentUser.id(authentication);
        Assignment assignment = assignmentAccess.loadForStudent(assignmentId, studentId);

        if (!assignment.allows(body.contentType())) {
            throw ApiException.unsupportedType(ErrorCodes.TYPE_NOT_ALLOWED, "File type not allowed: " + body.contentType());
        }
        if (body.sizeBytes() > assignment.getMaxBytes()) {
            throw ApiException.payloadTooLarge(
                    ErrorCodes.FILE_TOO_LARGE,
                    "File size " + body.sizeBytes() + " exceeds max allowed bytes of " + assignment.getMaxBytes());
        }

        UUID uploadId = UUID.randomUUID();
        String objectPath = objectPath(assignmentId, studentId, uploadId);
        SignedUrl signed = storagePort.signPut(objectPath, body.contentType(), body.sizeBytes(), URL_TTL);

        // A typical direct upload records nothing until finalize: there is no database write here.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new DirectUploadResponse(uploadId, signed.url(), signed.headers(), signed.expiresAt()));
    }

    @PostMapping("/api/assignments/{assignmentId}/direct-uploads/{uploadId}/finalize")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<BaselineSubmissionResponse> finalizeUpload(
            @PathVariable UUID assignmentId,
            @PathVariable UUID uploadId,
            Authentication authentication) {
        UUID studentId = CurrentUser.id(authentication);
        Assignment assignment = assignmentAccess.loadForStudent(assignmentId, studentId);

        // Built from the AUTHENTICATED student id, so nobody can finalize another student's object.
        String objectPath = objectPath(assignmentId, studentId, uploadId);

        Optional<ObjectInfo> stat = storagePort.stat(objectPath);
        if (stat.isEmpty()) {
            throw ApiException.notFound(ErrorCodes.BYTES_NOT_FOUND, "No uploaded bytes found for upload " + uploadId);
        }
        long size = stat.get().size();
        if (size > assignment.getMaxBytes()) {
            storagePort.delete(objectPath);
            throw ApiException.unprocessable(
                    ErrorCodes.SIZE_MISMATCH,
                    "Uploaded size " + size + " exceeds max allowed bytes of " + assignment.getMaxBytes());
        }

        // Stamped after the bytes have landed; this is the only difference from arm C.
        Instant submittedAt = clock.instant();
        Timing timing = deadlinePolicy.timing(submittedAt, assignment);

        BaselineSubmissionRow row = baselineSubmissionService.insertIfAbsentAndRead(
                UUID.randomUUID(), assignmentId, studentId, uploadId, objectPath, size,
                submittedAt, STAMP_SOURCE, timing);

        return ResponseEntity.ok(new BaselineSubmissionResponse(
                row.id(), row.submittedAt(), row.stampSource(), row.timing().name()));
    }

    private static String objectPath(UUID assignmentId, UUID studentId, UUID uploadId) {
        return "a/" + assignmentId + "/s/" + studentId + "/" + uploadId;
    }
}
