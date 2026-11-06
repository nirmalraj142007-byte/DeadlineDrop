package com.deadlinedrop.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "assignments")
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Column(name = "grace_seconds", nullable = false)
    private int graceSeconds;

    @Column(name = "max_bytes", nullable = false)
    private long maxBytes;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_types", columnDefinition = "text[]", nullable = false)
    private List<String> allowedTypes;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Assignment() {
    }

    public static Assignment create(UUID courseId,
                                    String title,
                                    Instant deadlineAt,
                                    int graceSeconds,
                                    long maxBytes,
                                    List<String> allowedTypes,
                                    UUID createdBy) {
        validate(graceSeconds, maxBytes);
        Assignment assignment = new Assignment();
        assignment.courseId = Objects.requireNonNull(courseId, "courseId must not be null");
        assignment.title = Objects.requireNonNull(title, "title must not be null");
        assignment.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        assignment.graceSeconds = graceSeconds;
        assignment.maxBytes = maxBytes;
        assignment.allowedTypes = allowedTypes != null ? new ArrayList<>(allowedTypes) : new ArrayList<>();
        assignment.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        return assignment;
    }

    public Instant graceEndsAt() {
        return deadlineAt.plusSeconds(graceSeconds);
    }

    public boolean allows(String contentType) {
        if (contentType == null || allowedTypes == null || allowedTypes.isEmpty()) {
            return false;
        }
        String trimmed = contentType.trim();
        String cleanType = trimmed.split(";")[0].trim();
        return allowedTypes.stream()
                .anyMatch(t -> t.equalsIgnoreCase(trimmed) || t.equalsIgnoreCase(cleanType));
    }

    public void update(String title, Instant deadlineAt, int graceSeconds, long maxBytes, List<String> allowedTypes) {
        validate(graceSeconds, maxBytes);
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        this.graceSeconds = graceSeconds;
        this.maxBytes = maxBytes;
        this.allowedTypes = allowedTypes != null ? new ArrayList<>(allowedTypes) : new ArrayList<>();
    }

    private static void validate(int graceSeconds, long maxBytes) {
        if (graceSeconds < 0 || graceSeconds > 3600) {
            throw new IllegalArgumentException("graceSeconds must be between 0 and 3600, got: " + graceSeconds);
        }
        if (maxBytes < 1 || maxBytes > 26214400L) {
            throw new IllegalArgumentException("maxBytes must be between 1 and 26214400, got: " + maxBytes);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getCourseId() {
        return courseId;
    }

    public String getTitle() {
        return title;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public int getGraceSeconds() {
        return graceSeconds;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public List<String> getAllowedTypes() {
        return allowedTypes;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
