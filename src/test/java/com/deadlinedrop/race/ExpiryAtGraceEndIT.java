package com.deadlinedrop.race;

import com.deadlinedrop.api.dto.CommitDtos.CommitResponse;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.race.HttpRaceSupport.Fixture;
import com.deadlinedrop.race.HttpRaceSupport.HttpRaceTest;
import com.deadlinedrop.service.ReconcilerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Grace admits only bytes that arrive inside the window. A commit whose bytes never came is still
 * COMMITTED one second before grace ends and EXPIRED one second after. Bytes that arrive later
 * (the signed URL is still valid) must not resurrect it: finalize answers EXPIRED, never UPLOADED.
 */
@HttpRaceTest
class ExpiryAtGraceEndIT {

    /**
     * Shorter than the 900 s default on purpose: the upload URL lives 15 minutes from commit, and
     * the late PUT at grace end + 1 s must still be accepted by storage for this test to mean
     * anything. With 900 s the URL (commit at T-10 s) would expire at T+890 s, before grace ends.
     */
    private static final int GRACE_SECONDS = 300;
    private static final int SIZE = 2048;

    private final Fixture fx;
    private final ReconcilerService reconciler;

    @Autowired
    ExpiryAtGraceEndIT(Fixture fx, ReconcilerService reconciler) {
        this.fx = fx;
        this.reconciler = reconciler;
    }

    @BeforeEach
    void setUp() {
        fx.setUp(GRACE_SECONDS);
    }

    @AfterEach
    void tearDown() {
        fx.clean();
    }

    @Test
    void missingBytesExpireExactlyAfterGraceAndLateBytesCannotRevive() throws Exception {
        Instant deadline = RaceTestSupport.DEADLINE;
        Instant graceEnd = deadline.plusSeconds(GRACE_SECONDS);

        fx.clock().set(deadline.minusSeconds(10));
        byte[] bytes = HttpRaceSupport.file(SIZE, 7);
        HttpResponse<String> committed = fx.commit(UUID.randomUUID(), HttpRaceSupport.sha256Hex(bytes), SIZE);
        assertThat(committed.statusCode()).isEqualTo(201);
        CommitResponse c = fx.commitResponse(committed);
        assertThat(c.timing()).isEqualTo("ON_TIME");
        UUID id = c.submissionId();

        fx.clock().set(graceEnd.minusSeconds(1));
        assertThat(reconciler.settle(id)).as("one second before grace ends").isEqualTo(Status.COMMITTED);
        assertThat(expiredEvents(id)).isZero();

        fx.clock().set(graceEnd.plusSeconds(1));
        assertThat(reconciler.settle(id)).as("one second after grace ends").isEqualTo(Status.EXPIRED);
        assertThat(expiredEvents(id)).isEqualTo(1);

        // The bytes arrive late; the URL itself is still valid, so storage accepts them.
        assertThat(c.urlExpiresAt()).isAfter(fx.clock().instant());
        assertThat(fx.put(c, bytes)).isEqualTo(200);

        HttpResponse<String> finalized = fx.finalizeSubmission(id);
        assertThat(finalized.statusCode()).isEqualTo(200);
        assertThat(fx.json(finalized, "status")).isEqualTo("EXPIRED");
        assertThat(fx.json(finalized, "uploadedAt")).isNull();
        assertThat(reconciler.settle(id)).as("the reconciler cannot revive it either").isEqualTo(Status.EXPIRED);

        assertThat(fx.status(id)).isEqualTo("EXPIRED");
        assertThat(fx.count("SELECT count(*) FROM submission_events WHERE submission_id = ? AND to_status = 'UPLOADED'", id))
                .isZero();
        assertThat(fx.count("SELECT count(*) FROM jobs WHERE submission_id = ?", id)).isZero();
        System.out.println("ExpiryAtGraceEndIT: COMMITTED at grace end - 1 s, EXPIRED at grace end + 1 s, "
                + "late PUT + finalize stayed EXPIRED");
    }

    private int expiredEvents(UUID id) {
        return fx.count("SELECT count(*) FROM submission_events WHERE submission_id = ? AND to_status = 'EXPIRED' "
                + "AND actor = 'RECONCILER'", id);
    }
}
