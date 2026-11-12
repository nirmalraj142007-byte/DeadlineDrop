package com.deadlinedrop.service;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.api.dto.DomainDtos.AssignmentDto;
import com.deadlinedrop.api.dto.DomainDtos.CreateAssignmentRequest;
import com.deadlinedrop.api.dto.DomainDtos.MySubmission;
import com.deadlinedrop.api.dto.DomainDtos.StudentAssignmentDto;
import com.deadlinedrop.api.dto.DomainDtos.UpdateAssignmentRequest;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Enrollment;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import com.deadlinedrop.repository.SubmissionRepository;
import com.deadlinedrop.repository.SubmissionRepository.LiveSubmissionSummary;
import org.springframework.context.annotation.Profile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Profile("!storage")
public class AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final SubmissionRepository submissionRepository;
    private final Clock clock;

    public AssignmentService(
            AssignmentRepository assignmentRepository,
            CourseRepository courseRepository,
            EnrollmentRepository enrollmentRepository,
            SubmissionRepository submissionRepository,
            Clock clock) {
        this.assignmentRepository = Objects.requireNonNull(assignmentRepository, "assignmentRepository must not be null");
        this.courseRepository = Objects.requireNonNull(courseRepository, "courseRepository must not be null");
        this.enrollmentRepository = Objects.requireNonNull(enrollmentRepository, "enrollmentRepository must not be null");
        this.submissionRepository = Objects.requireNonNull(submissionRepository, "submissionRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public AssignmentDto createAssignment(UUID courseId, UUID teacherId, CreateAssignmentRequest request) {
        Objects.requireNonNull(courseId, "courseId must not be null");
        Objects.requireNonNull(teacherId, "teacherId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Course not found: " + courseId));

        if (!course.getTeacherId().equals(teacherId)) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN, "Not course owner");
        }

        Assignment assignment = Assignment.create(
                courseId,
                request.title(),
                request.deadlineAt(),
                request.graceSeconds(),
                request.maxBytes(),
                request.allowedTypes(),
                teacherId
        );
        assignment = assignmentRepository.save(assignment);
        return toDto(assignment);
    }

    @Transactional(readOnly = true)
    public AssignmentDto getAssignment(UUID assignmentId, UUID userId, String role) {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found: " + assignmentId));

        Course course = courseRepository.findById(assignment.getCourseId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Course not found: " + assignment.getCourseId()));

        boolean isOwnerTeacher = course.getTeacherId().equals(userId);
        boolean isEnrolledStudent = enrollmentRepository.existsById(new Enrollment.Id(course.getId(), userId));
        boolean isAdmin = "ADMIN".equals(role);

        if (!isOwnerTeacher && !isEnrolledStudent && !isAdmin) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN, "Access denied to assignment " + assignmentId);
        }

        return toDto(assignment);
    }

    @Transactional
    public AssignmentDto updateAssignment(UUID assignmentId, UUID teacherId, UpdateAssignmentRequest request) {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(teacherId, "teacherId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found: " + assignmentId));

        UUID courseId = assignment.getCourseId();
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Course not found: " + courseId));

        if (!course.getTeacherId().equals(teacherId)) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN, "Not assignment owner");
        }

        if (clock.instant().isAfter(assignment.getDeadlineAt())) {
            throw ApiException.conflict(ErrorCodes.WRONG_STATE, "Cannot update assignment after deadline");
        }

        if (request.version() != assignment.getVersion()) {
            throw new ObjectOptimisticLockingFailureException(Assignment.class, assignment.getId());
        }

        assignment.update(
                request.title(),
                request.deadlineAt(),
                request.graceSeconds(),
                request.maxBytes(),
                request.allowedTypes()
        );

        Assignment updated = assignmentRepository.saveAndFlush(assignment);
        return toDto(updated);
    }

    @Transactional(readOnly = true)
    public List<StudentAssignmentDto> getMyAssignments(UUID studentId) {
        Objects.requireNonNull(studentId, "studentId must not be null");

        List<Enrollment> enrollments = enrollmentRepository.findByIdStudentId(studentId);
        if (enrollments.isEmpty()) {
            return List.of();
        }

        List<UUID> courseIds = enrollments.stream().map(e -> e.getId().courseId()).toList();
        List<Assignment> assignments = assignmentRepository.findByCourseIdInOrderByDeadlineAtAsc(courseIds);
        if (assignments.isEmpty()) {
            return List.of();
        }

        Map<UUID, Course> courseMap = courseRepository.findAllById(courseIds).stream()
                .collect(Collectors.toMap(Course::getId, c -> c));

        Map<UUID, LiveSubmissionSummary> liveMap = submissionRepository.findLiveByStudent(studentId).stream()
                .collect(Collectors.toMap(LiveSubmissionSummary::assignmentId, s -> s));

        List<StudentAssignmentDto> result = new ArrayList<>(assignments.size());
        for (Assignment assignment : assignments) {
            Course course = courseMap.get(assignment.getCourseId());
            String courseCode = course != null ? course.getCode() : "";

            LiveSubmissionSummary live = liveMap.get(assignment.getId());
            MySubmission mySubmission = live != null
                    ? new MySubmission(live.id(), live.status().name(), live.timing().name(), live.committedAt())
                    : null;

            result.add(new StudentAssignmentDto(toDto(assignment), courseCode, mySubmission));
        }

        return result;
    }

    private static AssignmentDto toDto(Assignment a) {
        return new AssignmentDto(
                a.getId(),
                a.getCourseId(),
                a.getTitle(),
                a.getDeadlineAt(),
                a.getGraceSeconds(),
                a.graceEndsAt(),
                a.getMaxBytes(),
                a.getAllowedTypes(),
                a.getVersion()
        );
    }
}
