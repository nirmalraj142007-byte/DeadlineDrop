package com.deadlinedrop.service;

import com.deadlinedrop.domain.Submission.Actor;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.jobs.JobRepository;
import com.deadlinedrop.repository.SubmissionRepository;
import com.deadlinedrop.repository.TransitionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The only class that changes a submission's status after commit. Each transition is a guarded
 * UPDATE; only the caller that changed the row (rows affected == 1) writes the event and enqueues
 * follow-up work, in the same transaction. Every method returns the status now in the row, so a
 * caller that lost the race still learns the outcome.
 */
@Service
@Profile("!storage")
public class SubmissionStateMachine {

    /**
     * Test seam: called after a caller has read the status and before it writes. Production does
     * nothing; the race tests park both racers here so both read COMMITTED before either writes.
     */
    public interface RaceHook {
        default void afterStatusRead(UUID id) {
        }
    }

    /** The production hook: no pause, no side effect. */
    @Component
    @Profile("!storage")
    static class NoRaceHook implements RaceHook {
    }

    private final TransitionRepository transitions;
    private final SubmissionRepository submissions;
    private final JobRepository jobs;
    private final Clock clock;

    public SubmissionStateMachine(
            TransitionRepository transitions, SubmissionRepository submissions, JobRepository jobs, Clock clock) {
        this.transitions = Objects.requireNonNull(transitions, "transitions must not be null");
        this.submissions = Objects.requireNonNull(submissions, "submissions must not be null");
        this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** COMMITTED -> UPLOADED, at most once whoever calls first; enqueues verification with it. */
    @Transactional
    public Status markUploaded(UUID id, Actor actor) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Instant now = clock.instant();
        int rows = transitions.transition(id, Status.COMMITTED, Status.UPLOADED, now);
        if (rows == 1) {
            submissions.insertEvent(id, Status.COMMITTED, Status.UPLOADED, actor, now);
            jobs.enqueueVerify(id, now);
        }
        return currentStatus(id);
    }

    /** COMMITTED -> EXPIRED, only when the bytes never arrived and the grace window is over. */
    @Transactional
    public Status expire(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        Instant now = clock.instant();
        if (transitions.expireIfGraceOver(id, now) == 1) {
            submissions.insertEvent(id, Status.COMMITTED, Status.EXPIRED, Actor.RECONCILER, now);
        }
        return currentStatus(id);
    }

    private Status currentStatus(UUID id) {
        return submissions.findById(id)
                .orElseThrow(() -> new IllegalStateException("Submission " + id + " disappeared during a transition"))
                .status();
    }
}
