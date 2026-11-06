package com.deadlinedrop.repository;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Enrollment;
import com.deadlinedrop.domain.Role;
import com.deadlinedrop.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DomainRepositoryIT {

    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final AssignmentRepository assignmentRepository;

    public DomainRepositoryIT(@Autowired UserRepository userRepository,
                              @Autowired CourseRepository courseRepository,
                              @Autowired EnrollmentRepository enrollmentRepository,
                              @Autowired AssignmentRepository assignmentRepository) {
        this.userRepository = userRepository;
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Test
    @DisplayName("Save and reload User, verify role enum round-trips and case-insensitive lookup")
    void saveAndReloadUserRoleRoundTripAndCaseInsensitiveLookup() {
        String email = "teacher@dd.local";
        User user = User.create(email, "hashed_secret", Role.TEACHER, "Professor Plum");
        User saved = userRepository.saveAndFlush(user);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getVersion()).isEqualTo(0L);

        // Reload from database
        User reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo(email);
        assertThat(reloaded.getRole()).isEqualTo(Role.TEACHER);
        assertThat(reloaded.getDisplayName()).isEqualTo("Professor Plum");

        // Case-insensitive lookup
        Optional<User> foundUpper = userRepository.findByEmailIgnoreCase("TEACHER@dd.local");
        assertThat(foundUpper).isPresent();
        assertThat(foundUpper.get().getId()).isEqualTo(saved.getId());

        // Test display name mutation
        reloaded.changeDisplayName("Prof. Plum (Updated)");
        User updated = userRepository.saveAndFlush(reloaded);
        assertThat(updated.getDisplayName()).isEqualTo("Prof. Plum (Updated)");
        assertThat(updated.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Save and reload Course and find by teacher ID")
    void saveAndReloadCourse() {
        User teacher = userRepository.saveAndFlush(User.create("c-teacher@dd.local", "pw", Role.TEACHER, "Teacher"));
        Course course = Course.create("CS201", "Data Structures", teacher.getId());
        Course saved = courseRepository.saveAndFlush(course);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTeacherId()).isEqualTo(teacher.getId());

        List<Course> teacherCourses = courseRepository.findByTeacherId(teacher.getId());
        assertThat(teacherCourses).hasSize(1);
        assertThat(teacherCourses.getFirst().getCode()).isEqualTo("CS201");
    }

    @Test
    @DisplayName("Save and reload Enrollment with composite key, check existsById and findByIdStudentId")
    void saveAndReloadEnrollment() {
        User teacher = userRepository.saveAndFlush(User.create("e-teacher@dd.local", "pw", Role.TEACHER, "Teacher E"));
        User student = userRepository.saveAndFlush(User.create("e-student@dd.local", "pw", Role.STUDENT, "Student E"));
        Course course = courseRepository.saveAndFlush(Course.create("CS202", "Algorithms", teacher.getId()));

        Enrollment.Id enrollmentId = new Enrollment.Id(course.getId(), student.getId());
        Enrollment enrollment = new Enrollment(enrollmentId);
        enrollmentRepository.saveAndFlush(enrollment);

        assertThat(enrollmentRepository.existsById(enrollmentId)).isTrue();

        List<Enrollment> studentEnrollments = enrollmentRepository.findByIdStudentId(student.getId());
        assertThat(studentEnrollments).hasSize(1);
        assertThat(studentEnrollments.getFirst().getId().courseId()).isEqualTo(course.getId());
    }

    @Test
    @DisplayName("Save and reload Assignment with allowedTypes text[] round-trip, ordering, helper methods, and validation")
    void saveAndReloadAssignmentWithAllowedTypes() {
        User teacher = userRepository.saveAndFlush(User.create("a-teacher@dd.local", "pw", Role.TEACHER, "Teacher A"));
        Course course = courseRepository.saveAndFlush(Course.create("CS203", "Networks", teacher.getId()));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant deadline1 = now.plusSeconds(3600);
        Instant deadline2 = now.plusSeconds(7200);

        List<String> types = List.of("application/pdf", "application/zip");
        Assignment assign2 = Assignment.create(course.getId(), "Project 2", deadline2, 900, 20_000_000L, types, teacher.getId());
        Assignment assign1 = Assignment.create(course.getId(), "Project 1", deadline1, 600, 10_000_000L, types, teacher.getId());

        assignmentRepository.saveAndFlush(assign2);
        assignmentRepository.saveAndFlush(assign1);

        // Reload assign1 and verify properties
        Assignment reloaded = assignmentRepository.findById(assign1.getId()).orElseThrow();
        assertThat(reloaded.getAllowedTypes()).containsExactly("application/pdf", "application/zip");
        assertThat(reloaded.getGraceSeconds()).isEqualTo(600);
        assertThat(reloaded.graceEndsAt()).isEqualTo(deadline1.plusSeconds(600));

        // allows(...) checks
        assertThat(reloaded.allows("application/pdf")).isTrue();
        assertThat(reloaded.allows("APPLICATION/PDF")).isTrue();
        assertThat(reloaded.allows("application/pdf; charset=utf-8")).isTrue();
        assertThat(reloaded.allows("image/png")).isFalse();
        assertThat(reloaded.allows(null)).isFalse();

        // Ordered lookup by courseIds
        List<Assignment> ordered = assignmentRepository.findByCourseIdInOrderByDeadlineAtAsc(List.of(course.getId()));
        assertThat(ordered).hasSize(2);
        assertThat(ordered.get(0).getTitle()).isEqualTo("Project 1");
        assertThat(ordered.get(1).getTitle()).isEqualTo("Project 2");

        // Validation checks on update
        assertThrows(IllegalArgumentException.class, () ->
                reloaded.update("Invalid Grace", deadline1, 3601, 10_000_000L, types)
        );
        assertThrows(IllegalArgumentException.class, () ->
                reloaded.update("Invalid MaxBytes", deadline1, 900, 30_000_000L, types)
        );
    }

    @Test
    @DisplayName("Load the same assignment twice, update and save copy 1, then saving copy 2 throws ObjectOptimisticLockingFailureException")
    void optimisticLockingConflictThrowsException() {
        User teacher = userRepository.saveAndFlush(User.create("opt-teacher@dd.local", "pw", Role.TEACHER, "Opt Teacher"));
        Course course = courseRepository.saveAndFlush(Course.create("CS204", "OS", teacher.getId()));
        Assignment created = assignmentRepository.saveAndFlush(Assignment.create(
                course.getId(),
                "Initial OS Lab",
                Instant.now().plusSeconds(3600),
                900,
                5_000_000L,
                List.of("application/pdf"),
                teacher.getId()
        ));

        // Load copy 1 and copy 2 independently
        Assignment copy1 = assignmentRepository.findById(created.getId()).orElseThrow();
        Assignment copy2 = assignmentRepository.findById(created.getId()).orElseThrow();

        // Update and save copy 1 -> increments version in database to 1
        copy1.update("Updated by Session 1", copy1.getDeadlineAt(), copy1.getGraceSeconds(), copy1.getMaxBytes(), copy1.getAllowedTypes());
        assignmentRepository.saveAndFlush(copy1);

        // Update and save copy 2 -> copy 2 still has version 0 in memory, DB has version 1 -> optimistic lock failure
        copy2.update("Updated by Session 2", copy2.getDeadlineAt(), copy2.getGraceSeconds(), copy2.getMaxBytes(), copy2.getAllowedTypes());
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> {
            assignmentRepository.saveAndFlush(copy2);
        });
    }
}
