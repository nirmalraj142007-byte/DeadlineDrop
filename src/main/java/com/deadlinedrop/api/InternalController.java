package com.deadlinedrop.api;

import com.deadlinedrop.jobs.DrainService;
import com.deadlinedrop.jobs.DrainService.DrainResult;
import com.deadlinedrop.jobs.ReconcilerSweep;
import com.deadlinedrop.jobs.ReconcilerSweep.ReconcileResult;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Background work triggered over HTTP: the job queue drain and the reconciler sweep. Only the
 * scheduler may call these (ROLE_INTERNAL, granted by {@code InternalSecurityConfig}).
 */
@RestController
@Profile("!storage")
public class InternalController {

    /** Leaves headroom under Cloud Scheduler's and Cloud Run's request timeouts. */
    static final Duration DRAIN_BUDGET = Duration.ofSeconds(45);

    private final DrainService drainService;
    private final ReconcilerSweep reconcilerSweep;
    private final Clock clock;

    public InternalController(DrainService drainService, ReconcilerSweep reconcilerSweep, Clock clock) {
        this.drainService = Objects.requireNonNull(drainService, "drainService must not be null");
        this.reconcilerSweep = Objects.requireNonNull(reconcilerSweep, "reconcilerSweep must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @PostMapping("/internal/jobs/drain")
    @PreAuthorize("hasRole('INTERNAL')")
    public DrainResult drain() {
        return drainService.drain(DRAIN_BUDGET);
    }

    @PostMapping("/internal/reconcile")
    @PreAuthorize("hasRole('INTERNAL')")
    public ReconcileResult reconcile() {
        return reconcilerSweep.run(clock.instant());
    }
}
