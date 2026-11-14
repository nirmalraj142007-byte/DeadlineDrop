package com.deadlinedrop.service;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.api.dto.ReadDtos.DownloadUrlResponse;
import com.deadlinedrop.domain.Assignment;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Submission.Row;
import com.deadlinedrop.domain.Submission.Status;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.AssignmentRepository;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.SubmissionRepository;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.security.CurrentUser;
import com.deadlinedrop.storage.StoragePort;
import com.deadlinedrop.storage.StoragePort.SignedUrl;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Profile("!storage")
public class DownloadService {

    private static final Duration URL_TTL = Duration.ofMinutes(5);

    private static final String DOWNLOAD_ALL_SQL =
            "SELECT s.id, s.object_path, s.content_type, u.email "
                    + "FROM submissions s "
                    + "JOIN users u ON s.student_id = u.id "
                    + "WHERE s.assignment_id = ? "
                    + "  AND s.status IN ('UPLOADED', 'VERIFIED') "
                    + "ORDER BY u.email ASC, s.id ASC";

    private record SubDownloadItem(UUID id, String objectPath, String contentType, String email) {
    }

    private final SubmissionRepository submissionRepository;
    private final AssignmentRepository assignmentRepository;
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final StoragePort storagePort;
    private final Clock clock;
    private final JdbcTemplate jdbcTemplate;

    public DownloadService(
            SubmissionRepository submissionRepository,
            AssignmentRepository assignmentRepository,
            CourseRepository courseRepository,
            UserRepository userRepository,
            StoragePort storagePort,
            Clock clock,
            JdbcTemplate jdbcTemplate) {
        this.submissionRepository = Objects.requireNonNull(submissionRepository, "submissionRepository must not be null");
        this.assignmentRepository = Objects.requireNonNull(assignmentRepository, "assignmentRepository must not be null");
        this.courseRepository = Objects.requireNonNull(courseRepository, "courseRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse url(UUID submissionId, Authentication authentication) {
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        Objects.requireNonNull(authentication, "authentication must not be null");

        Row submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.SUBMISSION_NOT_FOUND,
                        "Submission not found: " + submissionId));

        Assignment assignment = assignmentRepository.findById(submission.assignmentId())
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.ASSIGNMENT_NOT_FOUND,
                        "Assignment not found: " + submission.assignmentId()));

        Course course = courseRepository.findById(assignment.getCourseId())
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.NOT_FOUND,
                        "Course not found: " + assignment.getCourseId()));

        UUID callerId = CurrentUser.id(authentication);
        if (!course.getTeacherId().equals(callerId)) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN, "Only course teacher may download submission " + submissionId);
        }

        if (submission.status() != Status.UPLOADED && submission.status() != Status.VERIFIED) {
            throw ApiException.conflict(
                    ErrorCodes.NOT_UPLOADED,
                    "Submission status " + submission.status() + " is not UPLOADED or VERIFIED");
        }

        User student = userRepository.findById(submission.studentId())
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.NOT_FOUND,
                        "Student not found: " + submission.studentId()));

        String localPart = emailLocalPart(student.getEmail());
        String ext = extensionForContentType(submission.contentType());
        String downloadFileName = localPart + "_" + submission.id() + "." + ext;

        SignedUrl signedUrl = storagePort.signGet(submission.objectPath(), downloadFileName, URL_TTL);
        return new DownloadUrlResponse(signedUrl.url(), signedUrl.expiresAt());
    }

    @Transactional(readOnly = true)
    public void validateDownloadAll(UUID assignmentId, UUID teacherId) {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(teacherId, "teacherId must not be null");

        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.ASSIGNMENT_NOT_FOUND,
                        "Assignment not found: " + assignmentId));

        Course course = courseRepository.findById(assignment.getCourseId())
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.NOT_FOUND,
                        "Course not found: " + assignment.getCourseId()));

        if (!course.getTeacherId().equals(teacherId)) {
            throw ApiException.forbidden(
                    ErrorCodes.FORBIDDEN,
                    "Only course teacher may download all submissions for assignment " + assignmentId);
        }

        if (clock.instant().isBefore(assignment.graceEndsAt())) {
            throw ApiException.conflict(
                    ErrorCodes.GRACE_NOT_OVER,
                    "Cannot download submissions before grace window ends at " + assignment.graceEndsAt());
        }
    }

    public void downloadAll(UUID assignmentId, UUID teacherId, OutputStream outputStream) {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(teacherId, "teacherId must not be null");
        Objects.requireNonNull(outputStream, "outputStream must not be null");

        validateDownloadAll(assignmentId, teacherId);

        List<SubDownloadItem> items = jdbcTemplate.query(
                DOWNLOAD_ALL_SQL,
                (rs, i) -> new SubDownloadItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("object_path"),
                        rs.getString("content_type"),
                        rs.getString("email")),
                assignmentId);

        try {
            ZipOutputStream zos = new ZipOutputStream(outputStream);
            for (SubDownloadItem item : items) {
                String localPart = emailLocalPart(item.email());
                String ext = extensionForContentType(item.contentType());
                String entryName = localPart + "_" + item.id() + "." + ext;

                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                try (InputStream in = storagePort.open(item.objectPath())) {
                    in.transferTo(zos);
                }
                zos.closeEntry();
            }
            zos.finish();
            zos.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stream zip of submissions for assignment " + assignmentId, e);
        }
    }

    public void downloadAll(UUID assignmentId, OutputStream outputStream) {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(outputStream, "outputStream must not be null");

        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.ASSIGNMENT_NOT_FOUND,
                        "Assignment not found: " + assignmentId));
        Course course = courseRepository.findById(assignment.getCourseId())
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCodes.NOT_FOUND,
                        "Course not found: " + assignment.getCourseId()));

        downloadAll(assignmentId, course.getTeacherId(), outputStream);
    }

    public static String emailLocalPart(String email) {
        if (email == null) {
            return "unknown";
        }
        int at = email.indexOf('@');
        return at != -1 ? email.substring(0, at) : email;
    }

    public static String extensionForContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "bin";
        }
        String base = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return switch (base) {
            case "application/pdf" -> "pdf";
            case "application/zip", "application/x-zip-compressed" -> "zip";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "text/plain" -> "txt";
            case "text/csv" -> "csv";
            case "application/json" -> "json";
            case "application/gzip", "application/x-gzip" -> "gz";
            case "application/x-tar" -> "tar";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/msword" -> "doc";
            default -> {
                int slash = base.indexOf('/');
                if (slash != -1 && slash < base.length() - 1) {
                    String sub = base.substring(slash + 1);
                    if (sub.startsWith("x-")) {
                        sub = sub.substring(2);
                    }
                    String sanitized = sub.replaceAll("[^a-zA-Z0-9]", "");
                    yield sanitized.isEmpty() ? "bin" : sanitized;
                }
                yield "bin";
            }
        };
    }
}
