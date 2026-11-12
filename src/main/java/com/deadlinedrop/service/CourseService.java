package com.deadlinedrop.service;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.api.dto.DomainDtos.CourseDto;
import com.deadlinedrop.api.dto.DomainDtos.CreateCourseRequest;
import com.deadlinedrop.api.dto.DomainDtos.EnrollRequest;
import com.deadlinedrop.api.dto.DomainDtos.EnrollResult;
import com.deadlinedrop.domain.Course;
import com.deadlinedrop.domain.Enrollment;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.CourseRepository;
import com.deadlinedrop.repository.EnrollmentRepository;
import com.deadlinedrop.repository.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("!storage")
public class CourseService {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;

    public CourseService(
            CourseRepository courseRepository,
            EnrollmentRepository enrollmentRepository,
            UserRepository userRepository) {
        this.courseRepository = Objects.requireNonNull(courseRepository, "courseRepository must not be null");
        this.enrollmentRepository = Objects.requireNonNull(enrollmentRepository, "enrollmentRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
    }

    @Transactional
    public CourseDto createCourse(UUID teacherId, CreateCourseRequest request) {
        Objects.requireNonNull(teacherId, "teacherId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        if (courseRepository.existsByCode(request.code())) {
            throw ApiException.conflict(ErrorCodes.VERSION_CONFLICT, "Course code already exists: " + request.code());
        }

        Course course = Course.create(request.code(), request.title(), teacherId);
        course = courseRepository.save(course);
        return new CourseDto(course.getId(), course.getCode(), course.getTitle(), 0);
    }

    @Transactional(readOnly = true)
    public List<CourseDto> getCoursesForTeacher(UUID teacherId) {
        Objects.requireNonNull(teacherId, "teacherId must not be null");
        List<Course> courses = courseRepository.findByTeacherId(teacherId);
        List<CourseDto> dtos = new ArrayList<>(courses.size());
        for (Course course : courses) {
            int students = enrollmentRepository.countByCourseId(course.getId());
            dtos.add(new CourseDto(course.getId(), course.getCode(), course.getTitle(), students));
        }
        return dtos;
    }

    @Transactional(readOnly = true)
    public List<CourseDto> getCoursesForStudent(UUID studentId) {
        Objects.requireNonNull(studentId, "studentId must not be null");
        List<Enrollment> enrollments = enrollmentRepository.findByIdStudentId(studentId);
        if (enrollments.isEmpty()) {
            return List.of();
        }
        List<UUID> courseIds = enrollments.stream().map(e -> e.getId().courseId()).toList();
        List<Course> courses = courseRepository.findAllById(courseIds);
        List<CourseDto> dtos = new ArrayList<>(courses.size());
        for (Course course : courses) {
            int students = enrollmentRepository.countByCourseId(course.getId());
            dtos.add(new CourseDto(course.getId(), course.getCode(), course.getTitle(), students));
        }
        return dtos;
    }

    @Transactional
    public EnrollResult enrollStudents(UUID courseId, UUID teacherId, EnrollRequest request) {
        Objects.requireNonNull(courseId, "courseId must not be null");
        Objects.requireNonNull(teacherId, "teacherId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Course not found: " + courseId));

        if (!course.getTeacherId().equals(teacherId)) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN, "Not course owner");
        }

        int enrolled = 0;
        int alreadyEnrolled = 0;
        List<String> unknownEmails = new ArrayList<>();
        Set<UUID> batchEnrolled = new HashSet<>();

        for (String email : request.emails()) {
            Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
            if (userOpt.isEmpty()) {
                unknownEmails.add(email);
            } else {
                User student = userOpt.get();
                Enrollment.Id id = new Enrollment.Id(courseId, student.getId());
                if (enrollmentRepository.existsById(id) || batchEnrolled.contains(student.getId())) {
                    alreadyEnrolled++;
                } else {
                    enrollmentRepository.save(Enrollment.enroll(courseId, student.getId()));
                    batchEnrolled.add(student.getId());
                    enrolled++;
                }
            }
        }

        return new EnrollResult(enrolled, alreadyEnrolled, unknownEmails);
    }
}
