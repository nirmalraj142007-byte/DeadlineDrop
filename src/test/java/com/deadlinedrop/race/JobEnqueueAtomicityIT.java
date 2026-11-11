package com.deadlinedrop.race;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.api.dto.CommitDtos.CommitResponse;
import com.deadlinedrop.jobs.JobRepository;
import com.deadlinedrop.race.HttpRaceSupport.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transactional job queue promise: a VERIFY job exists if and only if the UPLOADED transition
 * committed. Here the enqueue fails; the transition and its event must roll back with it, so the
 * submission is still COMMITTED and a retried finalize can finish the job cleanly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, HttpRaceSupport.Config.class, JobEnqueueAtomicityIT.FailingJobsConfig.class})
@TestPropertySource(properties = {HttpRaceSupport.STORAGE_SERVER_ENABLED, HttpRaceSupport.STORAGE_ROOT})
class JobEnqueueAtomicityIT {

    private static final int SIZE = 2048;

    /** Fails every enqueue while {@code failing} is set, as a database error on the jobs insert would. */
    static class FailingJobRepository extends JobRepository {

        private final AtomicBoolean failing = new AtomicBoolean(true);

        FailingJobRepository(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate);
        }

        @Override
        public void enqueueVerify(UUID submissionId, Instant now) {
            if (failing.get()) {
                throw new DataAccessResourceFailureException("Simulated failure inserting the VERIFY job for " + submissionId);
            }
            super.enqueueVerify(submissionId, now);
        }

        void recover() {
            failing.set(false);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingJobsConfig {
        @Bean
        @Primary
        FailingJobRepository failingJobRepository(JdbcTemplate jdbcTemplate) {
            return new FailingJobRepository(jdbcTemplate);
        }
    }

    private final Fixture fx;
    private final FailingJobRepository jobs;

    @Autowired
    JobEnqueueAtomicityIT(Fixture fx, FailingJobRepository jobs) {
        this.fx = fx;
        this.jobs = jobs;
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
    void failedEnqueueRollsBackTheUploadedTransition() throws Exception {
        byte[] bytes = HttpRaceSupport.file(SIZE, 3);
        HttpResponse<String> committed = fx.commit(UUID.randomUUID(), HttpRaceSupport.sha256Hex(bytes), SIZE);
        assertThat(committed.statusCode()).isEqualTo(201);
        CommitResponse c = fx.commitResponse(committed);
        UUID id = c.submissionId();
        assertThat(fx.put(c, bytes)).isEqualTo(200);

        HttpResponse<String> failed = fx.finalizeSubmission(id);

        assertThat(failed.statusCode()).as("finalize response: %s", failed.body()).isEqualTo(503);
        assertThat(failed.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(fx.json(failed, "code")).isEqualTo(ErrorCodes.DATABASE_UNAVAILABLE);
        assertThat(fx.json(failed, "requestId")).isNotBlank();

        assertThat(fx.status(id)).as("transition rolled back").isEqualTo("COMMITTED");
        assertThat(fx.jdbc().queryForObject("SELECT uploaded_at FROM submissions WHERE id = ?", java.sql.Timestamp.class, id)).isNull();
        assertThat(fx.count("SELECT count(*) FROM jobs")).isZero();
        assertThat(fx.count("SELECT count(*) FROM submission_events WHERE submission_id = ? AND to_status = 'UPLOADED'", id))
                .isZero();

        // Once the database recovers, the browser's retry finishes cleanly: one event, one job.
        jobs.recover();
        HttpResponse<String> retried = fx.finalizeSubmission(id);
        assertThat(retried.statusCode()).isEqualTo(200);
        assertThat(fx.json(retried, "status")).isEqualTo("UPLOADED");
        assertThat(fx.count("SELECT count(*) FROM jobs WHERE submission_id = ?", id)).isEqualTo(1);
        assertThat(fx.count("SELECT count(*) FROM submission_events WHERE submission_id = ? AND to_status = 'UPLOADED'", id))
                .isEqualTo(1);
        System.out.println("JobEnqueueAtomicityIT: failed enqueue left COMMITTED, 0 jobs, 0 UPLOADED events; "
                + "retry after recovery gave 1 job, 1 event");
    }
}
