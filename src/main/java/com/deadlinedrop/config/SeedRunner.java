package com.deadlinedrop.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@Profile("seed")
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);
    private static final int DEFAULT_STUDENTS = 50;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Environment env;
    private final ApplicationContext applicationContext;
    private boolean exitOnFinish = true;

    public SeedRunner(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            Clock clock,
            Environment env,
            ApplicationContext applicationContext) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.env = Objects.requireNonNull(env, "env must not be null");
        this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext must not be null");
    }

    void setExitOnFinish(boolean exitOnFinish) {
        this.exitOnFinish = exitOnFinish;
    }

    @Override
    public void run(ApplicationArguments args) {
        String seedPassword = env.getProperty("SEED_PASSWORD");
        if (seedPassword == null || seedPassword.isBlank()) {
            throw new IllegalStateException("SEED_PASSWORD environment variable or property is required");
        }

        int studentCount = DEFAULT_STUDENTS;
        String studentsStr = env.getProperty("SEED_STUDENTS");
        if (studentsStr != null && !studentsStr.isBlank()) {
            try {
                studentCount = Integer.parseInt(studentsStr.trim());
            } catch (NumberFormatException e) {
                log.warn("Invalid SEED_STUDENTS value '{}', defaulting to {}", studentsStr, DEFAULT_STUDENTS);
            }
        }

        log.info("Starting seed runner for {} students...", studentCount);

        // load-test accounts only; real pilot accounts get individual hashes in phase 30
        String passwordHash = passwordEncoder.encode(seedPassword);

        // 1. Seed admin and teacher
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, display_name) " +
                "VALUES ('admin@dd.local', ?, 'ADMIN'::user_role, 'Admin') ON CONFLICT DO NOTHING",
                passwordHash
        );
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, display_name) " +
                "VALUES ('teacher@dd.local', ?, 'TEACHER'::user_role, 'Teacher') ON CONFLICT DO NOTHING",
                passwordHash
        );

        UUID teacherId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE lower(email) = 'teacher@dd.local'",
                UUID.class
        );

        // 2. Seed students in batch
        List<Object[]> studentBatch = new ArrayList<>(studentCount);
        for (int i = 1; i <= studentCount; i++) {
            String email = String.format("student%04d@dd.local", i);
            String displayName = String.format("Student %04d", i);
            studentBatch.add(new Object[]{email, passwordHash, displayName});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO users (email, password_hash, role, display_name) " +
                "VALUES (?, ?, 'STUDENT'::user_role, ?) ON CONFLICT DO NOTHING",
                studentBatch
        );

        // 3. Create course DEMO101 owned by teacher
        jdbcTemplate.update(
                "INSERT INTO courses (code, title, teacher_id) " +
                "VALUES ('DEMO101', 'DEMO101', ?) ON CONFLICT DO NOTHING",
                teacherId
        );

        UUID courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM courses WHERE code = 'DEMO101'",
                UUID.class
        );

        // 4. Enroll every student
        List<UUID> studentIds = jdbcTemplate.query(
                "SELECT id FROM users WHERE role = 'STUDENT'::user_role",
                (rs, rowNum) -> rs.getObject("id", UUID.class)
        );

        List<Object[]> enrollBatch = new ArrayList<>(studentIds.size());
        for (UUID studentId : studentIds) {
            enrollBatch.add(new Object[]{courseId, studentId});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO enrollments (course_id, student_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                enrollBatch
        );

        // 5. Create assignment "Lab 1" due now + 7 days, grace 900 s, max 26214400 bytes, types application/pdf, application/zip, image/png
        Integer labCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM assignments WHERE course_id = ? AND title = 'Lab 1'",
                Integer.class,
                courseId
        );
        if (labCount == null || labCount == 0) {
            Instant now = clock.instant();
            Instant deadlineAt = now.plus(Duration.ofDays(7));
            jdbcTemplate.update(
                    "INSERT INTO assignments (course_id, title, deadline_at, grace_seconds, max_bytes, allowed_types, created_by) " +
                    "VALUES (?, 'Lab 1', ?, 900, 26214400, ARRAY['application/pdf', 'application/zip', 'image/png']::text[], ?)",
                    courseId,
                    Timestamp.from(deadlineAt),
                    teacherId
            );
        }

        log.info("Seed completed successfully: 1 admin, 1 teacher, {} students, course DEMO101, Lab 1.", studentCount);

        int exitCode = SpringApplication.exit(applicationContext, () -> 0);
        if (exitOnFinish) {
            System.exit(exitCode);
        }
    }
}
