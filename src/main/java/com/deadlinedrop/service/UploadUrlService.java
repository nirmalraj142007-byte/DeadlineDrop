package com.deadlinedrop.service;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Submission.Row;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.repository.SubmissionRepository;
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.storage.StoragePort.SignedUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Issues a fresh signed upload URL for a submission that is still COMMITTED, for a student whose
 * first URL expired or whose upload was interrupted. It never moves the stamp and never accepts
 * different bytes: the hash committed at the stamp is the only file this URL may carry.
 */
@Service
@Profile("!storage")
public class UploadUrlService {

    private static final Logger log = LoggerFactory.getLogger(UploadUrlService.class);

    private final SubmissionRepository submissions;
    private final AssignmentAccess assignmentAccess;
    private final StoragePort storage;
    private final Clock clock;

    public UploadUrlService(
            SubmissionRepository submissions,
            AssignmentAccess assignmentAccess,
            StoragePort storage,
            Clock clock) {
        this.submissions = Objects.requireNonNull(submissions, "submissions must not be null");
        this.assignmentAccess = Objects.requireNonNull(assignmentAccess, "assignmentAccess must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public SignedUrl reissue(UUID studentId, UUID submissionId, String sha256) {
        Objects.requireNonNull(studentId, "studentId must not be null");
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");

        Row row = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.SUBMISSION_NOT_FOUND,
                        "Submission " + submissionId + " not found"));
        if (!row.studentId().equals(studentId)) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN, "This submission belongs to another student");
        }
        if (row.status() != Status.COMMITTED) {
            throw ApiException.conflict(ErrorCodes.WRONG_STATE,
                    "Submission is " + row.status() + "; a new upload URL is only available while it is COMMITTED");
        }

        Assignment assignment = assignmentAccess.loadForStudent(row.assignmentId(), studentId);
        Instant now = clock.instant();
        if (now.isAfter(assignment.graceEndsAt())) {
            throw ApiException.gone(ErrorCodes.GRACE_OVER,
                    "The grace window ended at " + assignment.graceEndsAt() + "; no more uploads are accepted");
        }
        if (!row.sha256().equals(sha256)) {
            if (now.isAfter(assignment.getDeadlineAt())) {
                throw ApiException.forbidden(ErrorCodes.HASH_MISMATCH_AFTER_DEADLINE,
                        "After the deadline only the bytes committed before it can be uploaded");
            }
            throw ApiException.conflict(ErrorCodes.WRONG_STATE, "commit again to change the file");
        }

        try {
            return storage.signPut(row.objectPath(), row.contentType(), row.sizeBytes(), CommitService.UPLOAD_URL_TTL);
        } catch (RuntimeException e) {
            log.atError()
                    .addKeyValue("event", "reissue_sign_failed")
                    .addKeyValue("submissionId", submissionId)
                    .setCause(e)
                    .log("signing the re-issued upload URL failed");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.SIGNING_UNAVAILABLE,
                    "Could not create an upload URL right now; your commit stamp is safe, try again in a moment");
        }
    }
}
