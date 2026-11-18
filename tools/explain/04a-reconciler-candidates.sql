-- title: 4a. Reconciler sweep: candidates (SubmissionRepository.findCommittedBefore)
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM submissions WHERE status = 'COMMITTED' AND committed_at < now() - interval '10 minutes'
ORDER BY committed_at LIMIT 100;
