package com.deadlinedrop.service;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Submission.Actor;
import com.deadlinedrop.domain.Submission.Row;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.domain.Timing;
import com.deadlinedrop.repository.SubmissionRepository;
import com.deadlinedrop.repository.SubmissionRepository.Superseded;
import com.deadlinedrop.service.CommitService.CommitCommand;
import com.deadlinedrop.service.ReceiptService.Receipt;
import com.deadlinedrop.service.SubmissionStateMachine.RaceHook;
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.storage.StoragePort.ObjectInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The browser's finalize call: after the PUT, check the bytes landed with the committed size and
 * type, then move the submission to UPLOADED. The stamp is not touched here; it was taken at
 * commit. Finalize is safe to repeat and safe to race with the reconciler.
 *
 * <p>A commit made while the database was down has no row, only a signed receipt; finalize with
 * that receipt creates the row from the stamp inside it ({@link #finalizeWithReceipt}).
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
    private final ReceiptService receipts;
    private final AssignmentAccess assignmentAccess;
    private final DeadlinePolicy deadlinePolicy;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public FinalizeService(
            SubmissionRepository submissions,
            SubmissionStateMachine stateMachine,
            StoragePort storage,
            RaceHook raceHook,
            ReceiptService receipts,
            AssignmentAccess assignmentAccess,
            DeadlinePolicy deadlinePolicy,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.submissions = Objects.requireNonNull(submissions, "submissions must not be null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.raceHook = Objects.requireNonNull(raceHook, "raceHook must not be null");
        this.receipts = Objects.requireNonNull(receipts, "receipts must not be null");
        this.assignmentAccess = Objects.requireNonNull(assignmentAccess, "assignmentAccess must not be null");
        this.deadlinePolicy = Objects.requireNonNull(deadlinePolicy, "deadlinePolicy must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
        checkObjectMatches(stat.get(), row.id(), row.objectPath(), row.sizeBytes(), row.contentType());
        return stateMachine.markUploaded(submissionId, Actor.BROWSER_FINALIZE);
    }

    /**
     * Redeems a receipt issued while the database was down. Every check the commit skipped runs
     * here (enrolment, assignment limits), then the row is created with the stamp signed into the
     * receipt and moved to UPLOADED in the same transaction, so the reconciler never sees it as a
     * COMMITTED row whose grace is over. Redeeming the same receipt again returns the row's status.
     */
    public Status finalizeWithReceipt(UUID studentId, UUID submissionId, String token) {
        Objects.requireNonNull(studentId, "studentId must not be null");
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        Receipt receipt = receipts.verify(token)
                .orElseThrow(() -> ApiException.forbidden(ErrorCodes.RECEIPT_INVALID,
                        "The receipt was not signed by this server or has been altered"));
        if (!receipt.studentId().equals(studentId) || !receipt.submissionId().equals(submissionId)) {
            throw ApiException.forbidden(ErrorCodes.RECEIPT_INVALID,
                    "The receipt belongs to another student or another submission");
        }

        // A retry after the receipt was redeemed learns its status, even once the grace window is over.
        Optional<Row> redeemed = submissions.findByReceiptNonce(receipt.nonce());
        if (redeemed.isPresent()) {
            return redeemed.get().status();
        }

        String objectPath = CommitService.objectPath(receipt.assignmentId(), studentId, submissionId);
        Assignment assignment;
        try {
            assignment = assignmentAccess.loadForStudent(receipt.assignmentId(), studentId);
        } catch (ApiException e) {
            if (ErrorCodes.NOT_ENROLLED.equals(e.getCode())) {
                deleteObject(submissionId, objectPath, "not_enrolled");
            }
            throw e;
        }

        Instant from = receipt.committedAt().isAfter(assignment.getDeadlineAt())
                ? receipt.committedAt() : assignment.getDeadlineAt();
        Instant redeemBy = from.plusSeconds(assignment.getGraceSeconds());
        if (clock.instant().isAfter(redeemBy)) {
            throw ApiException.gone(ErrorCodes.RECEIPT_STALE,
                    "The receipt had to be redeemed by " + redeemBy + "; its grace window is over");
        }
        if (!assignment.allows(receipt.contentType())) {
            deleteObject(submissionId, objectPath, "type_not_allowed");
            throw ApiException.unsupportedType(ErrorCodes.TYPE_NOT_ALLOWED,
                    "File type not allowed: " + receipt.contentType());
        }
        if (receipt.sizeBytes() > assignment.getMaxBytes()) {
            deleteObject(submissionId, objectPath, "file_too_large");
            throw ApiException.payloadTooLarge(ErrorCodes.FILE_TOO_LARGE, "File size " + receipt.sizeBytes()
                    + " exceeds the maximum of " + assignment.getMaxBytes() + " bytes");
        }

        Optional<ObjectInfo> stat = storage.stat(objectPath);
        if (stat.isEmpty()) {
            throw ApiException.notFound(ErrorCodes.BYTES_NOT_FOUND,
                    "The uploaded file has not arrived in storage yet; retry finalize");
        }
        checkObjectMatches(stat.get(), submissionId, objectPath, receipt.sizeBytes(), receipt.contentType());

        try {
            return redeem(receipt, assignment, objectPath);
        } catch (DuplicateKeyException first) {
            // A concurrent redeem of the same receipt, or a concurrent commit for this assignment,
            // won the insert. The second pass replays the first, or supersedes the other commit.
            try {
                return redeem(receipt, assignment, objectPath);
            } catch (DuplicateKeyException second) {
                throw ApiException.conflict(ErrorCodes.VERSION_CONFLICT,
                        "Another submission for this assignment is being recorded; retry finalize");
            }
        }
    }

    private Status redeem(Receipt r, Assignment assignment, String objectPath) {
        Status status = transactionTemplate.execute(tx -> {
            Optional<Row> existing = submissions.findByReceiptNonce(r.nonce());
            if (existing.isPresent()) {
                return existing.get().status();
            }
            Instant now = clock.instant();
            Timing timing = deadlinePolicy.timing(r.committedAt(), assignment);
            String requestHash = CommitService.requestHash(new CommitCommand(r.studentId(), r.assignmentId(),
                    r.nonce(), r.sha256(), r.sizeBytes(), r.contentType(), r.committedAt()));

            // The newest commit wins. A submission committed after this receipt's stamp (the student
            // committed again once the database was back) stays live; the receipt's submission is
            // recorded, already superseded by it, so its history is kept and nothing live is touched.
            Optional<Row> live = submissions.findLiveForUpdate(r.assignmentId(), r.studentId());
            if (live.isPresent() && live.get().committedAt().isAfter(r.committedAt())) {
                return recordAsSuperseded(r, objectPath, timing, requestHash, live.get(), now);
            }

            List<Superseded> superseded = submissions.supersedeLive(r.assignmentId(), r.studentId(), now);
            Row row = new Row(r.submissionId(), r.assignmentId(), r.studentId(), r.nonce(), requestHash, r.sha256(),
                    r.sizeBytes(), r.contentType(), objectPath, Status.COMMITTED, timing, r.committedAt(),
                    null, null, null, null, null, null, null, null);
            submissions.insertCommittedFromReceipt(row, r.nonce());
            submissions.insertEvent(r.submissionId(), null, Status.COMMITTED, Actor.RECEIPT, r.committedAt());
            for (Superseded old : superseded) {
                submissions.insertEvent(old.id(), old.previousStatus(), Status.SUPERSEDED, Actor.NEW_COMMIT, now);
            }
            submissions.linkSupersededBy(superseded.stream().map(Superseded::id).toList(), r.submissionId());

            log.atInfo()
                    .addKeyValue("event", "receipt_redeemed")
                    .addKeyValue("submissionId", r.submissionId())
                    .addKeyValue("committedAt", r.committedAt())
                    .addKeyValue("timing", timing)
                    .log("receipt redeemed: row created with the stamp signed at commit");
            // Joins this transaction: the row is never visible as COMMITTED to the reconciler.
            return stateMachine.markUploaded(r.submissionId(), Actor.BROWSER_FINALIZE);
        });
        return Objects.requireNonNull(status, "the redeem transaction returned no status");
    }

    /**
     * Inserts the receipt's submission as SUPERSEDED by {@code newer}, which is left as it is. The
     * events show both steps (committed at the signed stamp, then superseded on redeem), and no
     * verify job is enqueued: a superseded submission is never graded.
     */
    private Status recordAsSuperseded(Receipt r, String objectPath, Timing timing, String requestHash,
                                      Row newer, Instant now) {
        Row row = new Row(r.submissionId(), r.assignmentId(), r.studentId(), r.nonce(), requestHash, r.sha256(),
                r.sizeBytes(), r.contentType(), objectPath, Status.SUPERSEDED, timing, r.committedAt(),
                null, null, null, null, now, newer.id(), null, null);
        submissions.insertSupersededFromReceipt(row, r.nonce());
        submissions.insertEvent(r.submissionId(), null, Status.COMMITTED, Actor.RECEIPT, r.committedAt());
        submissions.insertEvent(r.submissionId(), Status.COMMITTED, Status.SUPERSEDED, Actor.RECEIPT, now);

        log.atInfo()
                .addKeyValue("event", "receipt_redeemed_superseded")
                .addKeyValue("submissionId", r.submissionId())
                .addKeyValue("committedAt", r.committedAt())
                .addKeyValue("supersededBy", newer.id())
                .addKeyValue("newerCommittedAt", newer.committedAt())
                .log("receipt redeemed after a newer commit; recorded as superseded, the newer one stays live");
        return Status.SUPERSEDED;
    }

    private void checkObjectMatches(ObjectInfo info, UUID submissionId, String objectPath,
                                    long committedSize, String committedType) {
        if (info.size() != committedSize) {
            deleteObject(submissionId, objectPath, "size_mismatch");
            throw ApiException.unprocessable(ErrorCodes.SIZE_MISMATCH,
                    "Uploaded " + info.size() + " bytes but committed " + committedSize + "; upload the committed file again");
        }
        if (!baseType(info.contentType()).equals(baseType(committedType))) {
            deleteObject(submissionId, objectPath, "content_type_mismatch");
            throw ApiException.unprocessable(ErrorCodes.CONTENT_TYPE_MISMATCH,
                    "Uploaded type " + info.contentType() + " but committed " + committedType);
        }
    }

    /** Reads what the finalize response reports; the caller has already passed the ownership check. */
    public Details describe(UUID submissionId) {
        Row row = submissions.findById(submissionId)
                .orElseThrow(() -> new IllegalStateException("Submission " + submissionId + " vanished after finalize"));
        return new Details(row.id(), row.status(), row.timing(), row.uploadedAt());
    }

    private void deleteObject(UUID submissionId, String objectPath, String reason) {
        log.atWarn()
                .addKeyValue("event", "finalize_rejected")
                .addKeyValue("reason", reason)
                .addKeyValue("submissionId", submissionId)
                .log("uploaded object cannot be accepted for this commit; deleting it");
        storage.delete(objectPath);
    }

    private static String baseType(String contentType) {
        return contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
    }
}
