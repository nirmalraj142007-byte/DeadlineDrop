package com.deadlinedrop.api;

import com.deadlinedrop.api.dto.CommitDtos.SubmissionStatusResponse;
import com.deadlinedrop.security.CurrentUser;
import com.deadlinedrop.service.FinalizeService;
import com.deadlinedrop.service.FinalizeService.Details;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public FinalizeController(FinalizeService finalizeService) {
        this.finalizeService = Objects.requireNonNull(finalizeService, "finalizeService must not be null");
    }

    @PostMapping("/api/submissions/{id}/finalize")
    @PreAuthorize("hasRole('STUDENT')")
    public SubmissionStatusResponse finalizeSubmission(@PathVariable UUID id, Authentication authentication) {
        finalizeService.finalize(CurrentUser.id(authentication), id);
        Details details = finalizeService.describe(id);
        return new SubmissionStatusResponse(
                details.submissionId(), details.status().name(), details.timing().name(), details.uploadedAt());
    }
}
