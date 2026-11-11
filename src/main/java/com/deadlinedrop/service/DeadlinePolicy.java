package com.deadlinedrop.service;

import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Timing;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * Evaluates whether a timestamped submission qualifies as ON_TIME or LATE against the assignment deadline.
 */
@Component
public class DeadlinePolicy {

    public Timing timing(Instant stamp, Assignment a) {
        Objects.requireNonNull(stamp, "stamp must not be null");
        Objects.requireNonNull(a, "assignment must not be null");
        return !stamp.isAfter(a.getDeadlineAt()) ? Timing.ON_TIME : Timing.LATE;
    }
}
