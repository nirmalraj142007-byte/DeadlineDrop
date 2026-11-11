package com.deadlinedrop.race;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.race.RaceTestSupport.BarrierRaceHook;
import com.deadlinedrop.race.RaceTestSupport.Fixture;
import com.deadlinedrop.race.RaceTestSupport.RoundResult;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.service.CommitService;
import com.deadlinedrop.service.FinalizeService;
import com.deadlinedrop.service.ReconcilerService;
import com.deadlinedrop.service.SubmissionStateMachine.RaceHook;
import com.deadlinedrop.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finalize and the reconciler both read COMMITTED, then both try COMMITTED -> UPLOADED. The
 * status guard must let exactly one of them win: one UPLOADED event, one VERIFY job, and both
 * callers see UPLOADED.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FinalizeVsReconcilerRaceIT.RaceConfig.class})
class FinalizeVsReconcilerRaceIT {

    private static final Path STORAGE_ROOT = RaceTestSupport.tempStorageRoot("dd-race-it-");

    @TestConfiguration
    static class RaceConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.of(RaceTestSupport.NOW);
        }

        @Bean
        @Primary
        RaceHook barrierRaceHook() {
            return new BarrierRaceHook();
        }
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("dd.storage.server.enabled", () -> "true");
        registry.add("dd.storage.root", () -> STORAGE_ROOT.toAbsolutePath().toString());
    }

    private final Fixture fixture;

    @Autowired
    FinalizeVsReconcilerRaceIT(MutableClock clock, UserRepository users, CourseRepository courses,
            EnrollmentRepository enrollments, AssignmentRepository assignments, CommitService commitService,
            FinalizeService finalizeService, ReconcilerService reconcilerService, JdbcTemplate jdbc,
            @LocalServerPort int port) {
        this.fixture = new Fixture(clock, users, courses, enrollments, assignments, commitService,
                finalizeService, reconcilerService, jdbc, port);
    }

    @BeforeEach
    void setUp() {
        fixture.setUp();
    }

    @AfterEach
    void tearDown() {
        fixture.clean();
    }

    @Test
    void exactlyOneActorMovesTheSubmissionToUploaded() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < RaceTestSupport.ITERATIONS; i++) {
                UUID id = fixture.committedWithBytes(i);

                RoundResult round = fixture.race(id, pool);

                assertThat(round.uploadedEvents()).as("UPLOADED events, iteration %d", i).isEqualTo(1);
                assertThat(round.jobs()).as("VERIFY jobs, iteration %d", i).isEqualTo(1);
                assertThat(round.finalized()).as("finalize result, iteration %d", i).isEqualTo(Status.UPLOADED);
                assertThat(round.settled()).as("reconciler result, iteration %d", i).isEqualTo(Status.UPLOADED);
            }
            System.out.printf("FinalizeVsReconcilerRaceIT: %d/%d iterations kept exactly one UPLOADED event%n",
                    RaceTestSupport.ITERATIONS, RaceTestSupport.ITERATIONS);
        } finally {
            pool.shutdownNow();
        }
    }
}
