package com.deadlinedrop.service;

import com.deadlinedrop.domain.BaselineSubmissionRow;
import com.deadlinedrop.domain.Timing;
import com.deadlinedrop.repository.BaselineSubmissionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Service orchestrating baseline submission persistence for experimental ablation arms.
 */
@Service
@Profile("!storage")
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

    /**
     * Inserts the row once per idempotency key and returns the stored row; on a replay that is the
     * original row, so the original stamp is kept.
     */
    @Transactional
    public BaselineSubmissionRow insertIfAbsentAndRead(
            UUID id,
            UUID assignmentId,
            UUID studentId,
            UUID idempotencyKey,
            String objectPath,
            long size,
            Instant submittedAt,
            String stampSource,
            Timing timing) {
        baselineSubmissionRepository.insertIfAbsent(
                id, assignmentId, studentId, idempotencyKey, objectPath, size, submittedAt, stampSource, timing);
        return baselineSubmissionRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Baseline submission missing after insert for key " + idempotencyKey));
    }

    @Transactional(readOnly = true)
    public int countByAssignment(UUID assignmentId) {
        return baselineSubmissionRepository.countByAssignment(assignmentId);
    }
}
