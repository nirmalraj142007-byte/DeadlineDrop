-- title: 7a. Student assignments: the student enrollments (EnrollmentRepository.findByIdStudentId)
EXPLAIN (ANALYZE, BUFFERS)
SELECT course_id, student_id, created_at FROM enrollments WHERE student_id = :'student'::uuid;

-- title: 7b. Student assignments: assignments of those courses (AssignmentRepository.findByCourseIdInOrderByDeadlineAtAsc)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM assignments WHERE course_id IN (:'course'::uuid) ORDER BY deadline_at ASC;

-- title: 7c. Student assignments: the student live submissions (SubmissionRepository.findLiveByStudent)
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, assignment_id, status, timing, committed_at FROM submissions
WHERE student_id = :'student'::uuid AND status NOT IN ('EXPIRED','SUPERSEDED');
