package com.deadlinedrop.race;

import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.race.HttpRaceSupport.Fixture;
import com.deadlinedrop.race.HttpRaceSupport.HttpRaceTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A flaky network makes the browser retry its commit. Here 20 copies of the same commit (same
 * Idempotency-Key, same body) arrive at once over HTTP. Exactly one may create the submission;
 * every other copy must replay it, and none may fail.
 */
@HttpRaceTest
class DuplicateRetryIT {

    private static final int THREADS = 20;
    private static final int ROUNDS = 10;
    private static final int SIZE = 2048;

    private final Fixture fx;

    @Autowired
    DuplicateRetryIT(Fixture fx) {
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
    void twentySimultaneousRetriesCreateOneSubmissionAndReuseWithAnotherHashIs409() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        UUID lastKey = null;
        try {
            for (int round = 0; round < ROUNDS; round++) {
                fx.cleanSubmissions();
                UUID key = UUID.randomUUID();
                String sha = HttpRaceSupport.sha256Hex(HttpRaceSupport.file(SIZE, round));

                List<HttpResponse<String>> responses = sendTogether(pool, key, sha);

                Set<String> ids = new HashSet<>();
                int created = 0;
                for (HttpResponse<String> r : responses) {
                    assertThat(r.statusCode()).as("round %d response: %s", round, r.body()).isIn(200, 201);
                    if (r.statusCode() == 201) {
                        created++;
                    }
                    ids.add(fx.json(r, "submissionId"));
                }
                assertThat(created).as("201 responses, round %d", round).isEqualTo(1);
                assertThat(ids).as("distinct submissionIds, round %d", round).hasSize(1);
                assertThat(fx.count("SELECT count(*) FROM submissions WHERE student_id = ?", fx.studentId()))
                        .as("submissions rows, round %d", round).isEqualTo(1);
                assertThat(fx.count("SELECT count(*) FROM submission_events WHERE actor = 'COMMIT'"))
                        .as("COMMIT events, round %d", round).isEqualTo(1);
                lastKey = key;
            }
            System.out.printf("DuplicateRetryIT: %d/%d rounds of %d simultaneous retries kept exactly one row%n",
                    ROUNDS, ROUNDS, THREADS);
        } finally {
            pool.shutdownNow();
        }

        String otherSha = HttpRaceSupport.sha256Hex(HttpRaceSupport.file(SIZE, 9_999));
        HttpResponse<String> reused = fx.commit(lastKey, otherSha, SIZE);
        assertThat(reused.statusCode()).isEqualTo(409);
        assertThat(fx.json(reused, "code")).isEqualTo(ErrorCodes.IDEMPOTENCY_KEY_REUSED);
        assertThat(fx.json(reused, "requestId")).isNotBlank();
        assertThat(fx.count("SELECT count(*) FROM submissions WHERE student_id = ?", fx.studentId())).isEqualTo(1);
    }

    private List<HttpResponse<String>> sendTogether(ExecutorService pool, UUID key, String sha) throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return fx.commit(key, sha, SIZE);
            }));
        }
        ready.await();
        go.countDown();
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (Future<HttpResponse<String>> f : futures) {
            responses.add(f.get());
        }
        return responses;
    }
}
