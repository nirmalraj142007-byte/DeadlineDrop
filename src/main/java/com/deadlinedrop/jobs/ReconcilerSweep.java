package com.deadlinedrop.jobs;

import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.repository.SubmissionRepository;
import com.deadlinedrop.service.ReconcilerService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Finds COMMITTED submissions whose browser never called finalize and settles each one: bytes
 * present -> UPLOADED (which enqueues verification), bytes absent after grace -> EXPIRED, else it
 * keeps waiting. Rows younger than {@link #MIN_AGE} are skipped: their browser is most likely
 * still uploading and will finalize on its own.
 */
@Service
@Profile("!storage")
public class ReconcilerSweep {

    /** {@code settled}: no longer COMMITTED (not counting expiries); {@code waiting}: still COMMITTED. */
    public record ReconcileResult(int settled, int expired, int waiting) {
    }

    static final Duration MIN_AGE = Duration.ofMinutes(2);
    static final int BATCH = 200;

    private final SubmissionRepository submissions;
    private final ReconcilerService reconciler;

    public ReconcilerSweep(SubmissionRepository submissions, ReconcilerService reconciler) {
        this.submissions = Objects.requireNonNull(submissions, "submissions must not be null");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler must not be null");
    }

    public ReconcileResult run(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        int settled = 0;
        int expired = 0;
        int waiting = 0;
        for (UUID id : submissions.findCommittedBefore(now.minus(MIN_AGE), BATCH)) {
            Status status = reconciler.settle(id);
            switch (status) {
                case COMMITTED -> waiting++;
                case EXPIRED -> expired++;
                default -> settled++;
            }
        }
        return new ReconcileResult(settled, expired, waiting);
    }
}
