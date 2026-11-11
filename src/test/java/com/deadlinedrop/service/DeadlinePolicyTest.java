package com.deadlinedrop.service;

import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Timing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlinePolicyTest {

    private DeadlinePolicy policy;
    private Assignment assignment;
    private Instant deadline;

    @BeforeEach
    void setUp() {
        policy = new DeadlinePolicy();
        deadline = Instant.parse("2026-11-20T23:59:59Z");
        assignment = Assignment.create(
                UUID.randomUUID(),
                "Lab 1",
                deadline,
                900,
                26214400,
                List.of("application/pdf"),
                UUID.randomUUID()
        );
    }

    @Test
    void stampOneMillisecondBeforeDeadlineIsOnTime() {
        Instant stamp = deadline.minusMillis(1);
        Timing timing = policy.timing(stamp, assignment);
        assertThat(timing).isEqualTo(Timing.ON_TIME);
    }

    @Test
    void stampExactlyAtDeadlineIsOnTime() {
        Instant stamp = deadline;
        Timing timing = policy.timing(stamp, assignment);
        assertThat(timing).isEqualTo(Timing.ON_TIME);
    }

    @Test
    void stampOneMillisecondAfterDeadlineIsLate() {
        Instant stamp = deadline.plusMillis(1);
        Timing timing = policy.timing(stamp, assignment);
        assertThat(timing).isEqualTo(Timing.LATE);
    }
}
