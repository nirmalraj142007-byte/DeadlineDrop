package com.deadlinedrop.api;

import com.deadlinedrop.api.dto.DomainDtos.CourseDto;
import com.deadlinedrop.api.dto.DomainDtos.CreateCourseRequest;
import com.deadlinedrop.api.dto.DomainDtos.EnrollRequest;
import com.deadlinedrop.api.dto.DomainDtos.EnrollResult;
import com.deadlinedrop.security.CurrentUser;
import com.deadlinedrop.service.CourseService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/courses")
@Profile("!storage")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = Objects.requireNonNull(courseService, "courseService must not be null");
    }

    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<CourseDto> createCourse(
            @Valid @RequestBody CreateCourseRequest request,
            Authentication authentication) {
        UUID teacherId = CurrentUser.id(authentication);
        CourseDto dto = courseService.createCourse(teacherId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'STUDENT')")
    public ResponseEntity<List<CourseDto>> listCourses(Authentication authentication) {
        UUID userId = CurrentUser.id(authentication);
        String role = CurrentUser.role(authentication);
        List<CourseDto> courses = "TEACHER".equals(role)
                ? courseService.getCoursesForTeacher(userId)
                : courseService.getCoursesForStudent(userId);
        return ResponseEntity.ok(courses);
    }

    @PostMapping("/{id}/enrollments")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<EnrollResult> enrollStudents(
            @PathVariable("id") UUID id,
            @Valid @RequestBody EnrollRequest request,
            Authentication authentication) {
        UUID teacherId = CurrentUser.id(authentication);
        EnrollResult result = courseService.enrollStudents(id, teacherId, request);
        return ResponseEntity.ok(result);
    }
}
