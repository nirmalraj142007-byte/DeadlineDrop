package com.deadlinedrop.api;

import com.deadlinedrop.api.dto.DomainDtos.AssignmentDto;
import com.deadlinedrop.api.dto.DomainDtos.CreateAssignmentRequest;
import com.deadlinedrop.api.dto.DomainDtos.StudentAssignmentDto;
import com.deadlinedrop.api.dto.DomainDtos.UpdateAssignmentRequest;
import com.deadlinedrop.security.CurrentUser;
import com.deadlinedrop.service.AssignmentService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@Profile("!storage")
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = Objects.requireNonNull(assignmentService, "assignmentService must not be null");
    }

    @PostMapping("/api/courses/{courseId}/assignments")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<AssignmentDto> createAssignment(
            @PathVariable("courseId") UUID courseId,
            @Valid @RequestBody CreateAssignmentRequest request,
            Authentication authentication) {
        UUID teacherId = CurrentUser.id(authentication);
        AssignmentDto dto = assignmentService.createAssignment(courseId, teacherId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping("/api/assignments/{id}")
    @PreAuthorize("hasAnyRole('TEACHER', 'STUDENT')")
    public ResponseEntity<AssignmentDto> getAssignment(
            @PathVariable("id") UUID id,
            Authentication authentication) {
        UUID userId = CurrentUser.id(authentication);
        String role = CurrentUser.role(authentication);
        AssignmentDto dto = assignmentService.getAssignment(id, userId, role);
        return ResponseEntity.ok(dto);
    }

    @PatchMapping("/api/assignments/{id}")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<AssignmentDto> updateAssignment(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateAssignmentRequest request,
            Authentication authentication) {
        UUID teacherId = CurrentUser.id(authentication);
        AssignmentDto dto = assignmentService.updateAssignment(id, teacherId, request);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/api/me/assignments")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<List<StudentAssignmentDto>> getMyAssignments(Authentication authentication) {
        UUID studentId = CurrentUser.id(authentication);
        List<StudentAssignmentDto> dtos = assignmentService.getMyAssignments(studentId);
        return ResponseEntity.ok(dtos);
    }
}
