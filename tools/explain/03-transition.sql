-- title: 3. Single-row state transition (TransitionRepository, rolled back)
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
UPDATE submissions SET status = 'UPLOADED'::submission_status, updated_at = now(),
  uploaded_at = CASE WHEN 'UPLOADED'::submission_status = 'UPLOADED' THEN now() ELSE uploaded_at END
WHERE id = :'committed_id'::uuid AND status = 'COMMITTED'::submission_status;
ROLLBACK;
