-- title: 5. Teacher dashboard counts (DashboardService.DASHBOARD_SQL)
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.student_id, u.email, u.display_name, sub.id AS submission_id,
       COALESCE(sub.status, 'NOT_SUBMITTED') AS status, sub.timing, sub.committed_at, sub.uploaded_at,
       sub.verified_at, COALESCE(sub.updated_at, u.updated_at) AS row_updated_at
FROM enrollments e
JOIN users u ON e.student_id = u.id
JOIN assignments a ON a.id = :'assignment'::uuid
LEFT JOIN LATERAL (
  SELECT s.id, s.status::text AS status, s.timing::text AS timing,
         s.committed_at, s.uploaded_at, s.verified_at, s.updated_at
  FROM submissions s
  WHERE s.assignment_id = a.id
    AND s.student_id = e.student_id
    AND (s.status NOT IN ('EXPIRED', 'SUPERSEDED') OR s.status IN ('EXPIRED', 'REJECTED_HASH_MISMATCH'))
  ORDER BY CASE WHEN s.status NOT IN ('EXPIRED', 'SUPERSEDED') THEN 0 ELSE 1 END, s.committed_at DESC, s.id DESC
  LIMIT 1
) sub ON true
WHERE e.course_id = a.course_id
ORDER BY u.email ASC;

-- title: 5b. Teacher dashboard: failed-job count (JobRepository.countFailedForAssignment)
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM jobs j JOIN submissions s ON j.submission_id = s.id
WHERE s.assignment_id = :'assignment'::uuid AND j.status = 'FAILED';
