package com.deadlinedrop.api;

import io.swagger.v3.oas.annotations.Operation;
import com.deadlinedrop.api.dto.CommitDtos.FinalizeRequest;
import com.deadlinedrop.api.dto.CommitDtos.ReissueRequest;
import com.deadlinedrop.api.dto.CommitDtos.SubmissionStatusResponse;
import com.deadlinedrop.api.dto.CommitDtos.UploadUrlResponse;
import com.deadlinedrop.security.CurrentUser;
import com.deadlinedrop.service.FinalizeService;
import com.deadlinedrop.service.FinalizeService.Details;
import com.deadlinedrop.service.UploadUrlService;
import com.deadlinedrop.storage.StoragePort.SignedUrl;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Arm C finalize: the browser calls this after its PUT to storage. It never changes the stamp;
 * it only confirms the committed bytes arrived. Repeating it returns the current status.
 */
@RestController
@Profile("!naive & !direct-finalize & !storage")
public class FinalizeController {

    /** A real receipt is about 300 characters; anything far longer is not one. */
    static final int MAX_BODY_CHARS = 2048;

    private final FinalizeService finalizeService;
    private final UploadUrlService uploadUrlService;
    private final ObjectMapper objectMapper;

    public FinalizeController(
            FinalizeService finalizeService, UploadUrlService uploadUrlService, ObjectMapper objectMapper) {
        this.finalizeService = Objects.requireNonNull(finalizeService, "finalizeService must not be null");
        this.uploadUrlService = Objects.requireNonNull(uploadUrlService, "uploadUrlService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * The body is optional and read as raw text, so a bodyless finalize keeps working whatever
     * Content-Type the client sends (curl --data "" sends a form type) and only a non-empty body is
     * parsed as {@code {"receipt": "..."}}.
     */
    @Operation(summary = "Confirm the committed bytes arrived in storage; never moves the stamp. "
            + "Send {\"receipt\": ...} when the commit returned one (the database was down at commit).")
    @PostMapping("/api/submissions/{id}/finalize")
    @PreAuthorize("hasRole('STUDENT')")
    public SubmissionStatusResponse finalizeSubmission(
            @PathVariable UUID id,
            @RequestBody(required = false) String body,
            Authentication authentication) {
        UUID studentId = CurrentUser.id(authentication);
        String receipt = receiptFrom(body);
        if (receipt != null) {
            finalizeService.finalizeWithReceipt(studentId, id, receipt);
        } else {
            finalizeService.finalize(studentId, id);
        }
        Details details = finalizeService.describe(id);
        return new SubmissionStatusResponse(
                details.submissionId(), details.status().name(), details.timing().name(), details.uploadedAt());
    }

    private String receiptFrom(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        if (body.length() > MAX_BODY_CHARS) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_FAILED, "Finalize body is too long");
        }
        FinalizeRequest request;
        try {
            request = objectMapper.readValue(body, FinalizeRequest.class);
        } catch (JacksonException e) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_FAILED,
                    "Finalize body must be empty or {\"receipt\": \"...\"}");
        }
        String receipt = request == null ? null : request.receipt();
        return receipt == null || receipt.isBlank() ? null : receipt;
    }

    @Operation(summary = "Get a fresh upload URL for a still-COMMITTED submission with the same hash")
    @PostMapping("/api/submissions/{id}/upload-url")
    @PreAuthorize("hasRole('STUDENT')")
    public UploadUrlResponse reissueUploadUrl(
            @PathVariable UUID id, @Valid @RequestBody ReissueRequest body, Authentication authentication) {
        SignedUrl signed = uploadUrlService.reissue(CurrentUser.id(authentication), id, body.sha256());
        return new UploadUrlResponse(signed.url(), signed.headers(), signed.expiresAt());
    }
}
