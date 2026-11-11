package com.deadlinedrop.race;

import com.deadlinedrop.api.dto.CommitDtos.CommitResponse;
import com.deadlinedrop.race.HttpRaceSupport.Fixture;
import com.deadlinedrop.race.HttpRaceSupport.HttpRaceTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A student uploads, then changes their mind and commits a new version. The old version is
 * superseded and linked to the new one. Two new versions committed at the same moment must never
 * leave two live rows (or a 500), and the database itself refuses a second live row.
 */
@HttpRaceTest
class SupersedeOnResubmitIT {

    private static final int SIZE = 2048;
    private static final int ROUNDS = 10;

    private final Fixture fx;

    @Autowired
    SupersedeOnResubmitIT(Fixture fx) {
        this.fx = fx;
    }

    @BeforeEach
    void setUp() {
        fx.setUp(900);
    }

    @AfterEach
    void tearDown() {
        fx.clean();
    }

    @Test
    void resubmitSupersedesAndConcurrentResubmitsLeaveOneLiveRow() throws Exception {
        // v1: commit, PUT, finalize -> UPLOADED
        byte[] v1Bytes = HttpRaceSupport.file(SIZE, 1);
        HttpResponse<String> v1Commit = fx.commit(UUID.randomUUID(), HttpRaceSupport.sha256Hex(v1Bytes), SIZE);
        assertThat(v1Commit.statusCode()).isEqualTo(201);
        CommitResponse v1 = fx.commitResponse(v1Commit);
        assertThat(fx.put(v1, v1Bytes)).isEqualTo(200);
        HttpResponse<String> v1Final = fx.finalizeSubmission(v1.submissionId());
        assertThat(v1Final.statusCode()).isEqualTo(200);
        assertThat(fx.json(v1Final, "status")).isEqualTo("UPLOADED");

        // v2 with a new key supersedes v1
        HttpResponse<String> v2Commit = fx.commit(UUID.randomUUID(),
                HttpRaceSupport.sha256Hex(HttpRaceSupport.file(SIZE, 2)), SIZE);
        assertThat(v2Commit.statusCode()).isEqualTo(201);
        UUID v2 = fx.commitResponse(v2Commit).submissionId();

        assertThat(fx.status(v1.submissionId())).isEqualTo("SUPERSEDED");
        assertThat(fx.jdbc().queryForObject("SELECT superseded_by FROM submissions WHERE id = ?", UUID.class,
                v1.submissionId())).isEqualTo(v2);
        assertThat(fx.count("SELECT count(*) FROM submission_events WHERE submission_id = ? AND from_status = 'UPLOADED' "
                + "AND to_status = 'SUPERSEDED' AND actor = 'NEW_COMMIT'", v1.submissionId())).isEqualTo(1);
        assertThat(fx.status(v2)).isEqualTo("COMMITTED");

        // v3 and v4 (different keys) arrive together, repeated to hit the interleavings
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int conflicts = 0;
        try {
            for (int round = 0; round < ROUNDS; round++) {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                int seed = 100 + 2 * round;
                Future<HttpResponse<String>> a = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return fx.commit(UUID.randomUUID(), HttpRaceSupport.sha256Hex(HttpRaceSupport.file(SIZE, seed)), SIZE);
                });
                Future<HttpResponse<String>> b = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return fx.commit(UUID.randomUUID(), HttpRaceSupport.sha256Hex(HttpRaceSupport.file(SIZE, seed + 1)), SIZE);
                });
                ready.await();
                go.countDown();

                for (HttpResponse<String> r : List.of(a.get(), b.get())) {
                    assertThat(r.statusCode()).as("round %d response: %s", round, r.body()).isIn(201, 409);
                    if (r.statusCode() == 409) {
                        conflicts++;
                    }
                }
                assertThat(liveRows()).as("live rows after round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(fx.count("SELECT count(*) FROM submissions WHERE student_id = ? AND status = 'SUPERSEDED' "
                + "AND superseded_by IS NULL", fx.studentId())).as("superseded rows without a successor").isZero();
        System.out.printf("SupersedeOnResubmitIT: %d/%d concurrent resubmit rounds left exactly one live row "
                + "(%d answered 409 VERSION_CONFLICT, 0 answered 500)%n", ROUNDS, ROUNDS, conflicts);

        // The partial unique index is the last line of defence: a raw second live row is refused.
        assertThatThrownBy(this::insertSecondLiveRow)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    Throwable cause = ((DataAccessException) e).getMostSpecificCause();
                    assertThat(cause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getSQLState()).isEqualTo("23505");
                    assertThat(cause.getMessage()).contains("ux_live_submission");
                });
    }

    private int liveRows() {
        return fx.count("SELECT count(*) FROM submissions WHERE assignment_id = ? AND student_id = ? "
                + "AND status NOT IN ('EXPIRED','SUPERSEDED')", fx.assignmentId(), fx.studentId());
    }

    private void insertSecondLiveRow() {
        UUID id = UUID.randomUUID();
        fx.jdbc().update("INSERT INTO submissions (id, assignment_id, student_id, idempotency_key, request_hash, sha256, "
                        + "size_bytes, content_type, object_path, status, timing, committed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'COMMITTED', 'ON_TIME', ?)",
                id, fx.assignmentId(), fx.studentId(), UUID.randomUUID(), "f".repeat(64), "e".repeat(64),
                SIZE, "application/pdf", "raw/" + id, Timestamp.from(fx.clock().instant()));
    }
}
