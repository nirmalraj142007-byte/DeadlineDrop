package com.deadlinedrop.service;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.api.dto.DomainDtos.ExportRow;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Profile("!storage")
public class ExportService {

    private final AssignmentRepository assignmentRepository;
    private final CourseRepository courseRepository;
    private final ExportSource exportSource;

    public ExportService(
            AssignmentRepository assignmentRepository,
            CourseRepository courseRepository,
            ExportSource exportSource) {
        this.assignmentRepository = Objects.requireNonNull(assignmentRepository, "assignmentRepository must not be null");
        this.courseRepository = Objects.requireNonNull(courseRepository, "courseRepository must not be null");
        this.exportSource = Objects.requireNonNull(exportSource, "exportSource must not be null");
    }

    @Transactional(readOnly = true)
    public List<ExportRow> getExportRows(UUID assignmentId, UUID callerId, String callerRole) {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(callerId, "callerId must not be null");

        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found: " + assignmentId));

        Course course = courseRepository.findById(assignment.getCourseId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Course not found: " + assignment.getCourseId()));

        boolean isAdmin = "ADMIN".equals(callerRole);
        boolean isOwnerTeacher = "TEACHER".equals(callerRole) && course.getTeacherId().equals(callerId);

        if (!isAdmin && !isOwnerTeacher) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN, "Not allowed to export assignment " + assignmentId);
        }

        return exportSource.findExportRows(assignmentId);
    }
}
