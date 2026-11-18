-- title: 4b. Reconciler sweep: expire one past grace (TransitionRepository.EXPIRE_SQL, rolled back)
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
UPDATE submissions SET status = 'EXPIRED', expired_at = now(), updated_at = now()
WHERE id = :'committed_id'::uuid AND status = 'COMMITTED' AND EXISTS (SELECT 1 FROM assignments a
  WHERE a.id = submissions.assignment_id AND a.deadline_at + make_interval(secs => a.grace_seconds) < now());
ROLLBACK;
