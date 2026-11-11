package com.deadlinedrop.race;

import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Enrollment;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import com.deadlinedrop.repository.TransitionRepository;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.service.CommitService;
import com.deadlinedrop.service.CommitService.CommitCommand;
import com.deadlinedrop.service.CommitService.CommitResult;
import com.deadlinedrop.service.FinalizeService;
import com.deadlinedrop.service.ReconcilerService;
import com.deadlinedrop.service.SubmissionStateMachine.RaceHook;
import com.deadlinedrop.support.MutableClock;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared pieces of the finalize-vs-reconciler race tests.
 */
final class RaceTestSupport {

    static final int ITERATIONS = 50;
    static final int FILE_BYTES = 2048;
    static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    static final Instant DEADLINE = Instant.parse("2026-11-20T23:59:59Z");

    private RaceTestSupport() {
    }

    /** Makes both racers wait here until both have read COMMITTED, so neither writes first by luck. */
    static final class BarrierRaceHook implements RaceHook {

        private final CyclicBarrier barrier = new CyclicBarrier(2);

        @Override
        public void afterStatusRead(UUID id) {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted at the race barrier for " + id, e);
            } catch (BrokenBarrierException | TimeoutException e) {
                barrier.reset();
                throw new IllegalStateException("The other racer never reached the barrier for " + id, e);
            }
        }
    }

    /**
     * The same UPDATE as production with the {@code AND status = ?} guard removed. Not final:
     * Spring subclasses repositories to translate SQL exceptions.
     */
    static class UnguardedTransitionRepository extends TransitionRepository {

        private static final String UNGUARDED_SQL =
                "UPDATE submissions SET status = ?::submission_status, updated_at = ?, "
                        + "uploaded_at = CASE WHEN ?::submission_status = 'UPLOADED' THEN ? ELSE uploaded_at END, "
                        + "verified_at = CASE WHEN ?::submission_status = 'VERIFIED' THEN ? ELSE verified_at END, "
                        + "expired_at = CASE WHEN ?::submission_status = 'EXPIRED' THEN ? ELSE expired_at END "
                        + "WHERE id = ?";

        UnguardedTransitionRepository(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate);
        }

        @Override
        public int transition(UUID id, Status from, Status to, Instant now) {
            var ts = utc(now);
            return jdbcTemplate().update(UNGUARDED_SQL,
                    to.name(), ts, to.name(), ts, to.name(), ts, to.name(), ts, id);
        }
    }

    record RoundResult(Status finalized, Status settled, int uploadedEvents, int jobs) {
    }

    /** Creates the course data, commits through CommitService and races finalize against settle. */
    static final class Fixture {

        private final MutableClock clock;
        private final UserRepository users;
        private final CourseRepository courses;
        private final EnrollmentRepository enrollments;
        private final AssignmentRepository assignments;
        private final CommitService commitService;
        private final FinalizeService finalizeService;
        private final ReconcilerService reconcilerService;
        private final JdbcTemplate jdbc;
        private final HttpClient http = HttpClient.newHttpClient();
        private final int port;

        private UUID studentId;
        private UUID assignmentId;

        Fixture(MutableClock clock, UserRepository users, CourseRepository courses, EnrollmentRepository enrollments,
                AssignmentRepository assignments, CommitService commitService, FinalizeService finalizeService,
                ReconcilerService reconcilerService, JdbcTemplate jdbc, int port) {
            this.clock = clock;
            this.users = users;
            this.courses = courses;
            this.enrollments = enrollments;
            this.assignments = assignments;
            this.commitService = commitService;
            this.finalizeService = finalizeService;
            this.reconcilerService = reconcilerService;
            this.jdbc = jdbc;
            this.port = port;
        }

        void setUp() {
            clean();
            clock.set(NOW);
            User teacher = users.save(User.create("race-teacher@dd.local", "unused-hash", Role.TEACHER, "Race Teacher"));
            User student = users.save(User.create("race-student@dd.local", "unused-hash", Role.STUDENT, "Race Student"));
            Course course = courses.save(Course.create("RACE1", "Race course", teacher.getId()));
            enrollments.save(Enrollment.enroll(course.getId(), student.getId()));
            Assignment assignment = assignments.save(Assignment.create(course.getId(), "Race lab", DEADLINE, 900,
                    1_048_576L, List.of("application/pdf"), teacher.getId()));
            studentId = student.getId();
            assignmentId = assignment.getId();
        }

        void clean() {
            jdbc.update("DELETE FROM jobs");
            jdbc.update("DELETE FROM submission_events");
            jdbc.update("UPDATE submissions SET superseded_by = NULL");
            jdbc.update("DELETE FROM submissions");
            enrollments.deleteAll();
            assignments.deleteAll();
            courses.deleteAll();
            users.deleteAll();
        }

        /** A COMMITTED submission whose bytes are already in the in-process local storage server. */
        UUID committedWithBytes(int iteration) throws IOException, InterruptedException, URISyntaxException {
            byte[] bytes = new byte[FILE_BYTES];
            bytes[0] = (byte) iteration;
            String sha = HexFormat.of().formatHex(sha256(bytes));
            CommitResult result = commitService.commit(new CommitCommand(
                    studentId, assignmentId, UUID.randomUUID(), sha, bytes.length, "application/pdf", clock.instant()));

            URI signed = URI.create(result.signedUrl().url());
            URI local = new URI("http", null, "localhost", port, signed.getPath(), signed.getQuery(), null);
            HttpRequest.Builder put = HttpRequest.newBuilder(local).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
            result.signedUrl().headers().forEach(put::header);
            HttpResponse<String> response = http.send(put.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("PUT to local storage failed: " + response.statusCode() + " " + response.body());
            }
            return result.submissionId();
        }

        RoundResult race(UUID id, ExecutorService pool) throws InterruptedException, ExecutionException {
            CountDownLatch go = new CountDownLatch(1);
            Future<Status> finalized = pool.submit(() -> {
                go.await();
                return finalizeService.finalize(studentId, id);
            });
            Future<Status> settled = pool.submit(() -> {
                go.await();
                return reconcilerService.settle(id);
            });
            go.countDown();
            Status f = finalized.get();
            Status s = settled.get();
            Integer events = jdbc.queryForObject(
                    "SELECT count(*) FROM submission_events WHERE submission_id = ? AND to_status = 'UPLOADED'",
                    Integer.class, id);
            Integer jobs = jdbc.queryForObject("SELECT count(*) FROM jobs WHERE submission_id = ?", Integer.class, id);
            return new RoundResult(f, s, events == null ? 0 : events, jobs == null ? 0 : jobs);
        }

        private static byte[] sha256(byte[] bytes) {
            try {
                return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required by the JDK specification", e);
            }
        }
    }

    static Path tempStorageRoot(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Cannot create a temporary storage root", e);
        }
    }
}
