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
import com.deadlinedrop.service.ReceiptService.Receipt;
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.storage.StoragePort.SignedUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The commit step of the hash commitment: stamps the submission at arrival, records the hash it
 * binds to, supersedes any live submission, and only then hands out a signed upload URL. If the
 * database cannot be reached and receipts are enabled, the stamp goes into a signed receipt instead.
 */
@Service
@Profile("!storage")
public class CommitService {

    static final String STUDENT_KEY_INDEX = "ux_sub_student_key";
    static final String LIVE_SUBMISSION_INDEX = "ux_live_submission";
    static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(CommitService.class);

    public record CommitCommand(
            UUID studentId,
            UUID assignmentId,
            UUID idempotencyKey,
            String sha256,
            long sizeBytes,
            String contentType,
            Instant arrivedAt) {
    }

    /**
     * Outcome of a commit. {@code signedUrl} is null when signing failed or the submission is no
     * longer awaiting bytes; the stamp is never lost either way.
     *
     * <p>When the database could not be reached, {@code receipt} holds the signed receipt and
     * {@code timing} and {@code status} are null: no row exists yet, and on time or late is decided
     * when the receipt is redeemed at finalize.
     */
    public record CommitResult(
            UUID submissionId,
            Instant committedAt,
            Timing timing,
            Status status,
            SignedUrl signedUrl,
            boolean replayed,
            long dbMs,
            long signMs,
            String receipt) {

        public boolean receiptIssued() {
            return receipt != null;
        }
    }

    private record Outcome(Row row, boolean replayed) {
    }

    private final TransactionTemplate transactionTemplate;
    private final SubmissionRepository submissions;
    private final AssignmentAccess assignmentAccess;
    private final DeadlinePolicy deadlinePolicy;
    private final StoragePort storage;
    private final ReceiptService receipts;

    public CommitService(
            PlatformTransactionManager transactionManager,
            SubmissionRepository submissions,
            AssignmentAccess assignmentAccess,
            DeadlinePolicy deadlinePolicy,
            StoragePort storage,
            ReceiptService receipts) {
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.submissions = Objects.requireNonNull(submissions, "submissions must not be null");
        this.assignmentAccess = Objects.requireNonNull(assignmentAccess, "assignmentAccess must not be null");
        this.deadlinePolicy = Objects.requireNonNull(deadlinePolicy, "deadlinePolicy must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.receipts = Objects.requireNonNull(receipts, "receipts must not be null");
    }

    public CommitResult commit(CommitCommand c) {
        Objects.requireNonNull(c, "command must not be null");
        String requestHash = requestHash(c);

        long dbStart = System.nanoTime();
        Outcome outcome;
        try {
            outcome = runWithOneRetry(c, requestHash);
        } catch (RuntimeException e) {
            if (!receipts.enabled() || !DatabaseOutage.isOutage(e)) {
                throw e;
            }
            return issueReceipt(c, e, (System.nanoTime() - dbStart) / 1_000_000);
        }
        long dbMs = (System.nanoTime() - dbStart) / 1_000_000;

        Row row = outcome.row();
        // Signing happens after the transaction commits: the stamp must never be lost to a signing failure.
        SignedUrl signed = null;
        long signStart = System.nanoTime();
        if (row.status() == Status.COMMITTED) {
            try {
                signed = storage.signPut(row.objectPath(), row.contentType(), row.sizeBytes(), UPLOAD_URL_TTL);
            } catch (RuntimeException e) {
                log.atError()
                        .addKeyValue("event", "sign_failed")
                        .addKeyValue("submissionId", row.id())
                        .setCause(e)
                        .log("signing the upload URL failed; the commit stamp is kept, the browser must ask again");
            }
        }
        long signMs = (System.nanoTime() - signStart) / 1_000_000;

        return new CommitResult(row.id(), row.committedAt(), row.timing(), row.status(), signed,
                outcome.replayed(), dbMs, signMs, null);
    }

    /**
     * The database is down: put the arrival stamp in a signed receipt instead of a row. Nothing
     * that needs the database is checked here (enrolment, assignment limits, deadline); finalize
     * checks all of it when the receipt is redeemed. The upload URL is signed for the declared
     * size and type, so the bytes can move while the database is still down.
     */
    private CommitResult issueReceipt(CommitCommand c, RuntimeException outage, long dbMs) {
        UUID submissionId = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        String objectPath = objectPath(c.assignmentId(), c.studentId(), submissionId);

        long signStart = System.nanoTime();
        SignedUrl signed;
        try {
            signed = storage.signPut(objectPath, c.contentType(), c.sizeBytes(), UPLOAD_URL_TTL);
        } catch (RuntimeException signFailure) {
            // A receipt without an upload URL is useless: re-issuing a URL needs the database.
            // Nothing was stamped anywhere, so the browser simply retries the commit.
            signFailure.addSuppressed(outage);
            log.atError()
                    .addKeyValue("event", "receipt_sign_failed")
                    .setCause(signFailure)
                    .log("database unreachable and signing the upload URL failed; no receipt issued");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.SIGNING_UNAVAILABLE,
                    "Storage signing is unavailable; retry the request");
        }
        long signMs = (System.nanoTime() - signStart) / 1_000_000;

        // The token carries epoch milliseconds; the response must show the stamp the row will get.
        Instant stamp = c.arrivedAt().truncatedTo(ChronoUnit.MILLIS);
        String token = receipts.issue(new Receipt(submissionId, c.studentId(), c.assignmentId(), c.sha256(),
                c.sizeBytes(), c.contentType(), stamp, nonce));
        log.atWarn()
                .addKeyValue("event", "receipt_issued")
                .addKeyValue("submissionId", submissionId)
                .addKeyValue("assignmentId", c.assignmentId())
                .addKeyValue("committedAt", stamp)
                .addKeyValue("cause", outage.getClass().getSimpleName())
                .log("database unreachable at commit; issued a signed receipt instead of a row");
        return new CommitResult(submissionId, stamp, null, null, signed, false, dbMs, signMs, token);
    }

    static String objectPath(UUID assignmentId, UUID studentId, UUID submissionId) {
        return "a/" + assignmentId + "/s/" + studentId + "/" + submissionId;
    }

    /**
     * A unique violation aborts the Postgres transaction, so it can only be handled outside the
     * block. Both indexes are resolved by running the block again: with the same key the second
     * pass replays the winner's row; with another key the second pass sees the winner's live row
     * and supersedes it.
     */
    private Outcome runWithOneRetry(CommitCommand c, String requestHash) {
        try {
            return transactionTemplate.execute(status -> commitInTransaction(c, requestHash));
        } catch (DuplicateKeyException first) {
            if (!violates(first, STUDENT_KEY_INDEX) && !violates(first, LIVE_SUBMISSION_INDEX)) {
                throw first;
            }
            log.atInfo()
                    .addKeyValue("event", "commit_retry")
                    .addKeyValue("index", violates(first, STUDENT_KEY_INDEX) ? STUDENT_KEY_INDEX : LIVE_SUBMISSION_INDEX)
                    .log("concurrent commit detected; retrying once");
            try {
                return transactionTemplate.execute(status -> commitInTransaction(c, requestHash));
            } catch (DuplicateKeyException second) {
                throw ApiException.conflict(ErrorCodes.VERSION_CONFLICT,
                        "Another commit for this assignment is in progress; retry the request");
            }
        }
    }

    private Outcome commitInTransaction(CommitCommand c, String requestHash) {
        var existing = submissions.findByStudentAndKey(c.studentId(), c.idempotencyKey());
        if (existing.isPresent()) {
            Row row = existing.get();
            if (row.requestHash().equals(requestHash)) {
                return new Outcome(row, true);
            }
            throw ApiException.conflict(ErrorCodes.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used with a different request");
        }

        Assignment assignment = assignmentAccess.loadForStudent(c.assignmentId(), c.studentId());
        if (!assignment.allows(c.contentType())) {
            throw ApiException.unsupportedType(ErrorCodes.TYPE_NOT_ALLOWED, "File type not allowed: " + c.contentType());
        }
        if (c.sizeBytes() > assignment.getMaxBytes()) {
            throw ApiException.payloadTooLarge(ErrorCodes.FILE_TOO_LARGE,
                    "File size " + c.sizeBytes() + " exceeds the maximum of " + assignment.getMaxBytes() + " bytes");
        }

        // Late commits are accepted and marked LATE (docs/decisions.md D3).
        Timing timing = deadlinePolicy.timing(c.arrivedAt(), assignment);
        UUID id = UUID.randomUUID();
        String objectPath = objectPath(c.assignmentId(), c.studentId(), id);

        List<Superseded> superseded = submissions.supersedeLive(c.assignmentId(), c.studentId(), c.arrivedAt());

        Row row = new Row(id, c.assignmentId(), c.studentId(), c.idempotencyKey(), requestHash, c.sha256(),
                c.sizeBytes(), c.contentType(), objectPath, Status.COMMITTED, timing, c.arrivedAt(),
                null, null, null, null, null, null, null, null);
        submissions.insertCommitted(row);

        submissions.insertEvent(id, null, Status.COMMITTED, Actor.COMMIT, c.arrivedAt());
        for (Superseded old : superseded) {
            submissions.insertEvent(old.id(), old.previousStatus(), Status.SUPERSEDED, Actor.NEW_COMMIT, c.arrivedAt());
        }
        submissions.linkSupersededBy(superseded.stream().map(Superseded::id).toList(), id);

        return new Outcome(submissions.findById(id).orElseThrow(
                () -> new IllegalStateException("Submission " + id + " vanished inside its own transaction")), false);
    }

    private static boolean violates(DuplicateKeyException e, String index) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(index);
    }

    static String requestHash(CommitCommand c) {
        String canonical = c.assignmentId() + "|" + c.sha256() + "|" + c.sizeBytes() + "|" + c.contentType();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK specification", e);
        }
    }
}
