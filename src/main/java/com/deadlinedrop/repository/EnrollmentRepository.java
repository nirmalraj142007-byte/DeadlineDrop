package com.deadlinedrop.repository;

import com.deadlinedrop.domain.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Enrollment.Id> {
    boolean existsById(Enrollment.Id id);

    List<Enrollment> findByIdStudentId(UUID studentId);
}
