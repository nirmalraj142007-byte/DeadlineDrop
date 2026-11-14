package com.deadlinedrop.service;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.api.dto.ReadDtos.EventDto;
import com.deadlinedrop.api.dto.ReadDtos.ReceiptDto;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Submission.Row;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.SubmissionRepository;
import com.deadlinedrop.security.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Profile("!storage")
public class SubmissionQueryService {

    private final SubmissionRepository submissionRepository;
    private final AssignmentRepository assignmentRepository;
    private final CourseRepository courseRepository;

    public SubmissionQueryService(
            SubmissionRepository submissionRepository,
            AssignmentRepository assignmentRepository,
            CourseRepository courseRepository) {
        this.submissionRepository = Objects.requireNonNull(submissionRepository, "submissionRepository must not be null");
        this.assignmentRepository = Objects.requireNonNull(assignmentRepository, "assignmentRepository must not be null");
        this.courseRepository = Objects.requireNonNull(courseRepository, "courseRepository must not be null");
    }

    @Transactional(readOnly = true)
    public ReceiptDto receipt(UUID id, Authentication authentication) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(authentication, "authentication must not be null");

        Row submission = submissionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.SUBMISSION_NOT_FOUND,
                        "Submission not found: " + id));

        Assignment assignment = assignmentRepository.findById(submission.assignmentId())
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.ASSIGNMENT_NOT_FOUND,
                        "Assignment not found: " + submission.assignmentId()));

        Course course = courseRepository.findById(assignment.getCourseId())
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.NOT_FOUND,
                        "Course not found: " + assignment.getCourseId()));

        UUID callerId = CurrentUser.id(authentication);
        String role = CurrentUser.role(authentication);

        boolean isOwningStudent = submission.studentId().equals(callerId);
        boolean isCourseTeacher = course.getTeacherId().equals(callerId);
        boolean isAdmin = "ADMIN".equals(role);

        if (!isOwningStudent && !isCourseTeacher && !isAdmin) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN, "Access denied to submission " + id);
        }

        List<EventDto> events = submissionRepository.findEvents(id).stream()
                .map(e -> new EventDto(e.from(), e.to(), e.actor(), e.at()))
                .toList();

        return new ReceiptDto(
                submission.id(),
                submission.assignmentId(),
                assignment.getTitle(),
                submission.committedAt(),
                submission.sha256(),
                submission.sizeBytes(),
                submission.contentType(),
                submission.timing().name(),
                submission.status().name(),
                submission.uploadedAt(),
                submission.verifiedAt(),
                assignment.graceEndsAt(),
                events
        );
    }
}
