package com.deadlinedrop.jobs;

import com.deadlinedrop.domain.JobRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drains the transactional job queue for at most {@code budget}, one job per transaction. It is
 * called over HTTP (Cloud Scheduler in the cloud, the compose scheduler locally), never by
 * {@code @Scheduled}: Cloud Run throttles CPU outside requests, so background threads would stall.
 */
@Service
@Profile("!storage")
public class DrainService {

    /** What one drain call did; {@code ms} is wall time spent. */
    public record DrainResult(int processed, int retried, int failed, long ms) {
    }

    static final int MAX_ATTEMPTS = 5;

    private static final Logger log = LoggerFactory.getLogger(DrainService.class);

    private final JobRepository jobs;
    private final VerifierService verifier;
    private final Clock clock;
    private final TransactionTemplate jobTx;
    private final TransactionTemplate retryTx;

    public DrainService(JobRepository jobs, VerifierService verifier, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.jobTx = new TransactionTemplate(transactionManager);
        this.retryTx = new TransactionTemplate(transactionManager);
        this.retryTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public DrainResult drain(Duration budget) {
        Objects.requireNonNull(budget, "budget must not be null");
        // The budget is real elapsed time (request timeouts are real), so it uses nanoTime, not the app clock.
        long start = System.nanoTime();
        long deadline = start + budget.toNanos();
        int processed = 0;
        int retried = 0;
        int failed = 0;

        while (System.nanoTime() < deadline) {
            AtomicReference<JobRow> claimed = new AtomicReference<>();
            try {
                // Trade-off: claim, hash and markDone share one transaction, so the row lock and one
                // pool connection (of 5) are held while one object is hashed. Objects are at most
                // 25 MB and read in-region, so that is short; in exchange a crashed drainer simply
                // releases the lock and the job runs again, with no "in progress" state to clean up.
                Boolean ran = jobTx.execute(status -> {
                    Optional<JobRow> next = jobs.claimNext(clock.instant());
                    if (next.isEmpty()) {
                        return false;
                    }
                    claimed.set(next.get());
                    verifier.verify(next.get().submissionId());
                    jobs.markDone(next.get().id(), clock.instant());
                    return true;
                });
                if (!Boolean.TRUE.equals(ran)) {
                    break;
                }
                processed++;
            } catch (RuntimeException e) {
                JobRow job = claimed.get();
                if (job == null) {
                    // Failed before any job was claimed (database down): nothing to retry, so the caller sees it.
                    throw e;
                }
                if (recordFailure(job, e)) {
                    failed++;
                } else {
                    retried++;
                }
            }
        }
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        return new DrainResult(processed, retried, failed, ms);
    }

    /** Records a failed attempt in a new transaction; returns true if the job is now FAILED. */
    private boolean recordFailure(JobRow job, RuntimeException cause) {
        int attempts = job.attempts() + 1;
        String error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        boolean giveUp = attempts >= MAX_ATTEMPTS;
        Instant runAfter = clock.instant().plus(Duration.ofMinutes(1L << (attempts - 1)));

        Boolean applied = retryTx.execute(status -> giveUp
                ? jobs.markFailed(job.id(), attempts, error)
                : jobs.markRetry(job.id(), attempts, runAfter, error));

        log.atWarn()
                .setCause(cause)
                .addKeyValue("event", giveUp ? "job_failed" : "job_retry")
                .addKeyValue("jobId", job.id())
                .addKeyValue("submissionId", job.submissionId())
                .addKeyValue("attempts", attempts)
                .addKeyValue("runAfter", giveUp ? null : runAfter)
                .addKeyValue("applied", applied)
                .log("verify job attempt failed");
        return giveUp;
    }
}
