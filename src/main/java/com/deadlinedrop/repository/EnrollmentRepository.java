package com.deadlinedrop.repository;

import com.deadlinedrop.domain.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Enrollment.Id> {
    boolean existsById(Enrollment.Id id);

    List<Enrollment> findByIdStudentId(UUID studentId);

    @Query("SELECT count(e) FROM Enrollment e WHERE e.id.courseId = :courseId")
    int countByCourseId(@Param("courseId") UUID courseId);
}

