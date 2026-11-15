package com.deadlinedrop.api;

import io.swagger.v3.oas.annotations.Operation;
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

    private final FinalizeService finalizeService;
    private final UploadUrlService uploadUrlService;

    public FinalizeController(FinalizeService finalizeService, UploadUrlService uploadUrlService) {
        this.finalizeService = Objects.requireNonNull(finalizeService, "finalizeService must not be null");
        this.uploadUrlService = Objects.requireNonNull(uploadUrlService, "uploadUrlService must not be null");
    }

    @Operation(summary = "Confirm the committed bytes arrived in storage; never moves the stamp")
    @PostMapping("/api/submissions/{id}/finalize")
    @PreAuthorize("hasRole('STUDENT')")
    public SubmissionStatusResponse finalizeSubmission(@PathVariable UUID id, Authentication authentication) {
        finalizeService.finalize(CurrentUser.id(authentication), id);
        Details details = finalizeService.describe(id);
        return new SubmissionStatusResponse(
                details.submissionId(), details.status().name(), details.timing().name(), details.uploadedAt());
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
