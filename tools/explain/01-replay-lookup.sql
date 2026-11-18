-- title: 1. Idempotent replay lookup (SubmissionRepository.findByStudentAndKey)
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, assignment_id, student_id, idempotency_key, request_hash, sha256, size_bytes, content_type,
       object_path, status, timing, committed_at, db_committed_at, uploaded_at, verified_at,
       expired_at, superseded_at, superseded_by, created_at, updated_at
FROM submissions WHERE student_id = :'student'::uuid AND idempotency_key = :'live_key'::uuid;
