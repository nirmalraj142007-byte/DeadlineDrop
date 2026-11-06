package com.deadlinedrop.repository;

import com.deadlinedrop.domain.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {
    List<Assignment> findByCourseIdInOrderByDeadlineAtAsc(Collection<UUID> courseIds);
}
