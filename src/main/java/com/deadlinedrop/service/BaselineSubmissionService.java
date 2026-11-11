package com.deadlinedrop.service;

import com.deadlinedrop.domain.Timing;
import com.deadlinedrop.repository.BaselineSubmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Service orchestrating baseline submission persistence for experimental ablation arms.
 */
@Service
public class BaselineSubmissionService {

    private final BaselineSubmissionRepository baselineSubmissionRepository;

    public BaselineSubmissionService(BaselineSubmissionRepository baselineSubmissionRepository) {
        this.baselineSubmissionRepository = Objects.requireNonNull(baselineSubmissionRepository, "baselineSubmissionRepository must not be null");
    }

    @Transactional
    public void insert(
            UUID id,
            UUID assignmentId,
            UUID studentId,
            UUID idempotencyKeyOrNull,
            String objectPath,
            long size,
            Instant submittedAt,
            String stampSource,
            Timing timing) {
        baselineSubmissionRepository.insert(
                id,
                assignmentId,
                studentId,
                idempotencyKeyOrNull,
                objectPath,
                size,
                submittedAt,
                stampSource,
                timing
        );
    }

    @Transactional(readOnly = true)
    public int countByAssignment(UUID assignmentId) {
        return baselineSubmissionRepository.countByAssignment(assignmentId);
    }
}
