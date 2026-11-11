package com.deadlinedrop.jobs;

import com.deadlinedrop.domain.Submission.Row;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.repository.SubmissionRepository;
import com.deadlinedrop.service.SubmissionStateMachine;
import com.deadlinedrop.storage.StoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Closes the hash commitment: reads the stored object, hashes it, and compares the result with
 * the sha256 the browser committed before the upload. Equal -> VERIFIED; different ->
 * REJECTED_HASH_MISMATCH. Only UPLOADED rows are checked; anything else was settled elsewhere.
 */
@Service
@Profile("!storage")
public class VerifierService {

    private static final Logger log = LoggerFactory.getLogger(VerifierService.class);
    private static final int BUFFER_BYTES = 64 * 1024;

    private final SubmissionRepository submissions;
    private final SubmissionStateMachine stateMachine;
    private final StoragePort storage;

    public VerifierService(SubmissionRepository submissions, SubmissionStateMachine stateMachine, StoragePort storage) {
        this.submissions = Objects.requireNonNull(submissions, "submissions must not be null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
    }

    public void verify(UUID submissionId) {
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        Row row = submissions.findById(submissionId)
                .orElseThrow(() -> new IllegalStateException("VERIFY job points at missing submission " + submissionId));
        if (row.status() != Status.UPLOADED) {
            // Superseded, or already verified by an earlier attempt: nothing to check.
            return;
        }

        String actual = sha256Hex(row.objectPath());
        if (actual.equals(row.sha256())) {
            Status now = stateMachine.markVerified(submissionId);
            log.atInfo()
                    .addKeyValue("event", "submission_verified")
                    .addKeyValue("submissionId", submissionId)
                    .addKeyValue("status", now)
                    .log("stored bytes match the committed hash");
        } else {
            Status now = stateMachine.markRejected(submissionId);
            log.atWarn()
                    .addKeyValue("event", "submission_rejected")
                    .addKeyValue("submissionId", submissionId)
                    .addKeyValue("status", now)
                    .log("stored bytes do not match the committed hash");
        }
    }

    private String sha256Hex(String objectPath) {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream in = new DigestInputStream(storage.open(objectPath), digest)) {
            while (in.read(buffer) != -1) {
                // DigestInputStream updates the digest as it reads; the bytes themselves are not needed.
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + objectPath + " for verification", e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK specification", e);
        }
    }
}
