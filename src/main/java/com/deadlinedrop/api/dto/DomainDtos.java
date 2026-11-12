package com.deadlinedrop.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Data transfer objects for course, assignment, time, and export endpoints.
 */
public final class DomainDtos {

    private DomainDtos() {
    }

    public record CreateCourseRequest(
            @NotBlank @Size(max = 20) String code,
            @NotBlank @Size(max = 200) String title
    ) {
    }

    public record CourseDto(
            UUID id,
            String code,
            String title,
            int students
    ) {
    }

    public record EnrollRequest(
            @NotEmpty @Size(max = 2000) List<@Email String> emails
    ) {
    }

    public record EnrollResult(
            int enrolled,
            int alreadyEnrolled,
            List<String> unknownEmails
    ) {
    }

    public record CreateAssignmentRequest(
            @NotBlank @Size(max = 200) String title,
            @NotNull Instant deadlineAt,
            @Min(0) @Max(3600) int graceSeconds,
            @Min(1) @Max(26214400) long maxBytes,
            @NotEmpty List<@Pattern(regexp = "^[\\w.+-]+/[\\w.+-]+$") String> allowedTypes
    ) {
    }

    public record UpdateAssignmentRequest(
            @NotBlank @Size(max = 200) String title,
            @NotNull Instant deadlineAt,
            @Min(0) @Max(3600) int graceSeconds,
            @Min(1) @Max(26214400) long maxBytes,
            @NotEmpty List<@Pattern(regexp = "^[\\w.+-]+/[\\w.+-]+$") String> allowedTypes,
            @NotNull Long version
    ) {
    }

    public record AssignmentDto(
            UUID id,
            UUID courseId,
            String title,
            Instant deadlineAt,
            int graceSeconds,
            Instant graceEndsAt,
            long maxBytes,
            List<String> allowedTypes,
            long version
    ) {
    }

    public record MySubmission(
            UUID submissionId,
            String status,
            String timing,
            Instant stampedAt
    ) {
    }

    public record StudentAssignmentDto(
            AssignmentDto assignment,
            String courseCode,
            MySubmission mySubmission
    ) {
    }

    public record ExportRow(
            UUID studentId,
            String studentEmail,
            UUID submissionId,
            Instant stampedAt,
            Instant uploadedAt,
            String status,
            String timing,
            String stampSource
    ) {
    }

    public record TimeResponse(
            long epochMs
    ) {
    }
}
