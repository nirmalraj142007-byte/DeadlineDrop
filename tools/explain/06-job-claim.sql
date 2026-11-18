-- title: 6. Job claim with SKIP LOCKED (JobRepository.claimNext, rolled back)
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, submission_id, attempts FROM jobs WHERE status = 'PENDING' AND run_after <= now()
ORDER BY run_after LIMIT 1 FOR UPDATE SKIP LOCKED;
ROLLBACK;
