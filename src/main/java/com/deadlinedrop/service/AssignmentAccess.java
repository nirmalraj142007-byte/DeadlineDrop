package com.deadlinedrop.service;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Enrollment;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Validates assignment presence and student course enrolment.
 * Reused across Arm A, Arm B, and Arm C.
 */
@Service
@Profile("!storage")
@Transactional(readOnly = true)
public class AssignmentAccess {

    private final AssignmentRepository assignmentRepository;
    private final EnrollmentRepository enrollmentRepository;

    public AssignmentAccess(AssignmentRepository assignmentRepository, EnrollmentRepository enrollmentRepository) {
        this.assignmentRepository = Objects.requireNonNull(assignmentRepository, "assignmentRepository must not be null");
        this.enrollmentRepository = Objects.requireNonNull(enrollmentRepository, "enrollmentRepository must not be null");
    }

    /**
     * Loads the assignment and confirms that the student is actively enrolled in its course.
     *
     * @param assignmentId assignment UUID
     * @param studentId student user UUID
     * @return the requested {@link Assignment}
     * @throws ApiException 404 if assignment not found, 403 if student not enrolled
     */
    public Assignment loadForStudent(UUID assignmentId, UUID studentId) {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(studentId, "studentId must not be null");

        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.ASSIGNMENT_NOT_FOUND,
                        "Assignment not found: " + assignmentId
                ));

        boolean enrolled = enrollmentRepository.existsById(new Enrollment.Id(assignment.getCourseId(), studentId));
        if (!enrolled) {
            throw ApiException.forbidden(
                    ErrorCodes.NOT_ENROLLED,
                    "Student " + studentId + " is not enrolled in course " + assignment.getCourseId()
            );
        }

        return assignment;
    }
}
