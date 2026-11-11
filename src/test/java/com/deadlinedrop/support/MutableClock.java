package com.deadlinedrop.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test Clock whose current instant can be explicitly set and advanced.
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> currentInstant;
    private final ZoneId zone;

    public MutableClock(Instant initialInstant, ZoneId zone) {
        this.currentInstant = new AtomicReference<>(Objects.requireNonNull(initialInstant, "initialInstant must not be null"));
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
    }

    public static MutableClock of(Instant initialInstant) {
        return new MutableClock(initialInstant, ZoneOffset.UTC);
    }

    public static MutableClock epoch() {
        return of(Instant.EPOCH);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(currentInstant.get(), zone);
    }

    @Override
    public Instant instant() {
        return currentInstant.get();
    }

    public void set(Instant newInstant) {
        currentInstant.set(Objects.requireNonNull(newInstant, "newInstant must not be null"));
    }

    public void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        currentInstant.updateAndGet(instant -> instant.plus(duration));
    }
}
