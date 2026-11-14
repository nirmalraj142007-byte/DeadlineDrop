package com.deadlinedrop.api;

import com.deadlinedrop.api.dto.ReadDtos.ReceiptDto;
import com.deadlinedrop.service.SubmissionQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

@RestController
@Profile("!naive & !direct-finalize & !storage")
public class SubmissionQueryController {

    private final SubmissionQueryService submissionQueryService;

    public SubmissionQueryController(SubmissionQueryService submissionQueryService) {
        this.submissionQueryService = Objects.requireNonNull(submissionQueryService, "submissionQueryService must not be null");
    }

    @GetMapping("/api/submissions/{id}")
    @PreAuthorize("hasAnyRole('STUDENT', 'TEACHER', 'ADMIN')")
    public ResponseEntity<ReceiptDto> getSubmission(
            @PathVariable("id") UUID id,
            Authentication authentication) {
        ReceiptDto receipt = submissionQueryService.receipt(id, authentication);
        return ResponseEntity.ok(receipt);
    }
}
