package com.deadlinedrop.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "enrollments")
public class Enrollment {

    @Embeddable
    public record Id(
            @Column(name = "course_id", nullable = false) UUID courseId,
            @Column(name = "student_id", nullable = false) UUID studentId
    ) implements Serializable {
        public Id {
            Objects.requireNonNull(courseId, "courseId must not be null");
            Objects.requireNonNull(studentId, "studentId must not be null");
        }
    }

    @EmbeddedId
    private Id id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Enrollment() {
    }

    public Enrollment(Id id) {
        this.id = Objects.requireNonNull(id, "id must not be null");
    }

    public static Enrollment enroll(UUID courseId, UUID studentId) {
        return new Enrollment(new Id(courseId, studentId));
    }

    public Id getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
