package com.deadlinedrop.jobs;

import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.repository.SubmissionRepository;
import com.deadlinedrop.service.ReconcilerService;
import com.deadlinedrop.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
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
 *
 * <p>One broken row (unreadable object metadata, say) must not starve the rows behind it, so a
 * failure is logged and counted per row and the sweep moves on. The row stays COMMITTED and is
 * tried again on the next sweep.
 */
@Service
@Profile("!storage")
public class ReconcilerSweep {

    /**
     * {@code settled}: no longer COMMITTED (not counting expiries); {@code waiting}: still COMMITTED;
     * {@code errors}: rows whose settle threw and were left for the next sweep.
     */
    public record ReconcileResult(int settled, int expired, int waiting, int errors) {
    }

    static final Duration MIN_AGE = Duration.ofMinutes(2);
    static final int BATCH = 200;

    private static final Logger log = LoggerFactory.getLogger(ReconcilerSweep.class);

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
        int errors = 0;
        for (UUID id : submissions.findCommittedBefore(now.minus(MIN_AGE), BATCH)) {
            Status status;
            try {
                status = reconciler.settle(id);
            } catch (DataAccessResourceFailureException e) {
                // The database itself is unreachable: no other row can succeed either, and each would
                // wait out the pool's connection timeout. Stop and let the caller see the outage.
                throw e;
            } catch (RuntimeException e) {
                errors++;
                log.atError()
                        .setCause(e)
                        .addKeyValue("event", "reconcile_row_failed")
                        .addKeyValue("submissionId", id)
                        .addKeyValue("requestId", MDC.get(RequestIdFilter.MDC_KEY))
                        .log("could not settle a COMMITTED submission; it stays COMMITTED until the next sweep");
                continue;
            }
            switch (status) {
                case COMMITTED -> waiting++;
                case EXPIRED -> expired++;
                default -> settled++;
            }
        }
        return new ReconcileResult(settled, expired, waiting, errors);
    }
}
