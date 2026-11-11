package com.deadlinedrop.service;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.domain.Submission.Actor;
import com.deadlinedrop.domain.Submission.Row;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.domain.Timing;
import com.deadlinedrop.repository.SubmissionRepository;
import com.deadlinedrop.service.SubmissionStateMachine.RaceHook;
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.storage.StoragePort.ObjectInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The browser's finalize call: after the PUT, check the bytes landed with the committed size and
 * type, then move the submission to UPLOADED. The stamp is not touched here; it was taken at
 * commit. Finalize is safe to repeat and safe to race with the reconciler.
 */
@Service
@Profile("!storage")
public class FinalizeService {

    private static final Logger log = LoggerFactory.getLogger(FinalizeService.class);

    /** What the finalize response shows. */
    public record Details(UUID submissionId, Status status, Timing timing, Instant uploadedAt) {
    }

    private final SubmissionRepository submissions;
    private final SubmissionStateMachine stateMachine;
    private final StoragePort storage;
    private final RaceHook raceHook;

    public FinalizeService(
            SubmissionRepository submissions,
            SubmissionStateMachine stateMachine,
            StoragePort storage,
            RaceHook raceHook) {
        this.submissions = Objects.requireNonNull(submissions, "submissions must not be null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.raceHook = Objects.requireNonNull(raceHook, "raceHook must not be null");
    }

    public Status finalize(UUID studentId, UUID submissionId) {
        Objects.requireNonNull(studentId, "studentId must not be null");
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        Row row = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.SUBMISSION_NOT_FOUND,
                        "Submission " + submissionId + " not found"));
        if (!row.studentId().equals(studentId)) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN, "This submission belongs to another student");
        }
        if (row.status() != Status.COMMITTED) {
            return row.status();
        }
        raceHook.afterStatusRead(submissionId);

        Optional<ObjectInfo> stat = storage.stat(row.objectPath());
        if (stat.isEmpty()) {
            throw ApiException.notFound(ErrorCodes.BYTES_NOT_FOUND,
                    "The uploaded file has not arrived in storage yet; retry finalize");
        }
        ObjectInfo info = stat.get();
        if (info.size() != row.sizeBytes()) {
            deleteRejected(row, "size_mismatch");
            throw ApiException.unprocessable(ErrorCodes.SIZE_MISMATCH,
                    "Uploaded " + info.size() + " bytes but committed " + row.sizeBytes() + "; upload the committed file again");
        }
        if (!baseType(info.contentType()).equals(baseType(row.contentType()))) {
            deleteRejected(row, "content_type_mismatch");
            throw ApiException.unprocessable(ErrorCodes.CONTENT_TYPE_MISMATCH,
                    "Uploaded type " + info.contentType() + " but committed " + row.contentType());
        }
        return stateMachine.markUploaded(submissionId, Actor.BROWSER_FINALIZE);
    }

    /** Reads what the finalize response reports; the caller has already passed the ownership check. */
    public Details describe(UUID submissionId) {
        Row row = submissions.findById(submissionId)
                .orElseThrow(() -> new IllegalStateException("Submission " + submissionId + " vanished after finalize"));
        return new Details(row.id(), row.status(), row.timing(), row.uploadedAt());
    }

    private void deleteRejected(Row row, String reason) {
        log.atWarn()
                .addKeyValue("event", "finalize_rejected")
                .addKeyValue("reason", reason)
                .addKeyValue("submissionId", row.id())
                .log("uploaded object does not match the commit; deleting it so the student can upload again");
        storage.delete(row.objectPath());
    }

    private static String baseType(String contentType) {
        return contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
    }
}
