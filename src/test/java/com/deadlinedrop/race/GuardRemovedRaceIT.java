package com.deadlinedrop.race;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.race.RaceTestSupport.BarrierRaceHook;
import com.deadlinedrop.race.RaceTestSupport.Fixture;
import com.deadlinedrop.race.RaceTestSupport.RoundResult;
import com.deadlinedrop.race.RaceTestSupport.UnguardedTransitionRepository;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import com.deadlinedrop.repository.TransitionRepository;
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
 * This test exists to show the race test can fail. It runs the same scenario as
 * {@link FinalizeVsReconcilerRaceIT} with the {@code AND status = ?} guard removed from the
 * transition UPDATE, and asserts the invariant breaks: both actors write an UPLOADED event.
 *
 * <p>Delete the status guard from {@code TransitionRepository} and {@code FinalizeVsReconcilerRaceIT}
 * goes red. Never weaken this test: if it stops failing the invariant, the race test has stopped
 * testing anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, GuardRemovedRaceIT.UnguardedConfig.class})
class GuardRemovedRaceIT {

    private static final int MIN_VIOLATIONS = 45;
    private static final Path STORAGE_ROOT = RaceTestSupport.tempStorageRoot("dd-race-unguarded-it-");

    @TestConfiguration
    static class UnguardedConfig {
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

        @Bean
        @Primary
        TransitionRepository unguardedTransitionRepository(JdbcTemplate jdbcTemplate) {
            return new UnguardedTransitionRepository(jdbcTemplate);
        }
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("dd.storage.server.enabled", () -> "true");
        registry.add("dd.storage.root", () -> STORAGE_ROOT.toAbsolutePath().toString());
    }

    private final Fixture fixture;

    @Autowired
    GuardRemovedRaceIT(MutableClock clock, UserRepository users, CourseRepository courses,
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
    void withoutTheStatusGuardBothActorsWriteAnUploadedEvent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int violations = 0;
        try {
            for (int i = 0; i < RaceTestSupport.ITERATIONS; i++) {
                UUID id = fixture.committedWithBytes(i);
                RoundResult round = fixture.race(id, pool);
                if (round.uploadedEvents() >= 2) {
                    violations++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        System.out.printf("GuardRemovedRaceIT: invariant violated (2 UPLOADED events) in %d/%d iterations%n",
                violations, RaceTestSupport.ITERATIONS);
        assertThat(violations).as("iterations with two UPLOADED events").isGreaterThanOrEqualTo(MIN_VIOLATIONS);
    }
}
