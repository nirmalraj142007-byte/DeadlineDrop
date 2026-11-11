package com.deadlinedrop.service;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.domain.Submission.Actor;
import com.deadlinedrop.domain.Submission.Row;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.repository.SubmissionRepository;
import com.deadlinedrop.service.SubmissionStateMachine.RaceHook;
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.storage.StoragePort.ObjectInfo;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Settles one COMMITTED submission whose browser never called finalize (closed tab, dead
 * battery): bytes present with the committed size become UPLOADED; bytes absent after the grace
 * window become EXPIRED. The sweep that picks candidates comes later.
 */
@Service
@Profile("!storage")
public class ReconcilerService {

    private final SubmissionRepository submissions;
    private final SubmissionStateMachine stateMachine;
    private final StoragePort storage;
    private final RaceHook raceHook;

    public ReconcilerService(
            SubmissionRepository submissions,
            SubmissionStateMachine stateMachine,
            StoragePort storage,
            RaceHook raceHook) {
        this.submissions = Objects.requireNonNull(submissions, "submissions must not be null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.raceHook = Objects.requireNonNull(raceHook, "raceHook must not be null");
    }

    public Status settle(UUID submissionId) {
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        Row row = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.SUBMISSION_NOT_FOUND,
                        "Submission " + submissionId + " not found"));
        if (row.status() != Status.COMMITTED) {
            return row.status();
        }
        raceHook.afterStatusRead(submissionId);

        Optional<ObjectInfo> stat = storage.stat(row.objectPath());
        if (stat.isPresent()) {
            if (stat.get().size() == row.sizeBytes()) {
                return stateMachine.markUploaded(submissionId, Actor.RECONCILER);
            }
            // Wrong-size bytes are left for the browser's finalize to reject; nothing changes here.
            return Status.COMMITTED;
        }
        // Expires only if the grace window is over; otherwise the row stays COMMITTED.
        return stateMachine.expire(submissionId);
    }
}
