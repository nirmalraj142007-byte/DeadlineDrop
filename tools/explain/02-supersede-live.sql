-- title: 2. Supersede the live submission (SubmissionRepository.SUPERSEDE_SQL, rolled back)
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
WITH old AS (SELECT id, status FROM submissions
  WHERE assignment_id = :'assignment'::uuid AND student_id = :'student'::uuid AND status NOT IN ('EXPIRED','SUPERSEDED') FOR UPDATE)
UPDATE submissions s SET status = 'SUPERSEDED', superseded_at = now(), updated_at = now()
FROM old WHERE s.id = old.id RETURNING s.id, old.status;
ROLLBACK;
