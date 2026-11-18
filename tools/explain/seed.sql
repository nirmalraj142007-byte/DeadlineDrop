-- Scratch data for tools/explain.sh: 1 teacher, 1,000 students enrolled in one course, two assignments,
-- 1,000 submissions in mixed states and the jobs that go with them. Runs only in the throw-away
-- database dd_explain, so the placeholder password hash never reaches a real database.
INSERT INTO users (id, email, password_hash, role, display_name)
VALUES ('00000000-0000-0000-0000-000000000001', 'teacher@explain.local', 'placeholder', 'TEACHER', 'Teacher');

INSERT INTO users (id, email, password_hash, role, display_name)
SELECT ('00000000-0000-0000-0001-' || lpad(i::text, 12, '0'))::uuid,
       'student' || lpad(i::text, 4, '0') || '@explain.local',
       'placeholder', 'STUDENT', 'Student ' || lpad(i::text, 4, '0')
FROM generate_series(1, 1000) AS i;

INSERT INTO courses (id, code, title, teacher_id)
VALUES ('00000000-0000-0000-0002-000000000001', 'EXPLAIN1', 'Explain course', '00000000-0000-0000-0000-000000000001');

INSERT INTO enrollments (course_id, student_id)
SELECT '00000000-0000-0000-0002-000000000001', ('00000000-0000-0000-0001-' || lpad(i::text, 12, '0'))::uuid
FROM generate_series(1, 1000) AS i;

INSERT INTO assignments (id, course_id, title, deadline_at, grace_seconds, max_bytes, allowed_types, created_by)
VALUES ('00000000-0000-0000-0003-000000000001', '00000000-0000-0000-0002-000000000001', 'Lab 1',
        now() + interval '1 hour', 900, 10000000, '{application/pdf}', '00000000-0000-0000-0000-000000000001'),
       ('00000000-0000-0000-0003-000000000002', '00000000-0000-0000-0002-000000000001', 'Lab 2',
        now() + interval '7 days', 900, 10000000, '{application/pdf}', '00000000-0000-0000-0000-000000000001');

-- 850 live submissions (one per student 1..850) spread over the last hour:
-- i % 5 = 0 COMMITTED, 1 UPLOADED, 2 and 3 VERIFIED, 4 REJECTED_HASH_MISMATCH.
INSERT INTO submissions (id, assignment_id, student_id, idempotency_key, request_hash, sha256, size_bytes,
                         content_type, object_path, status, timing, committed_at, uploaded_at, verified_at)
SELECT gen_random_uuid(),
       '00000000-0000-0000-0003-000000000001',
       ('00000000-0000-0000-0001-' || lpad(i::text, 12, '0'))::uuid,
       gen_random_uuid(), repeat('f', 64), repeat('a', 64), 1000 + i, 'application/pdf',
       'a/live/' || i,
       (ARRAY['COMMITTED', 'UPLOADED', 'VERIFIED', 'VERIFIED', 'REJECTED_HASH_MISMATCH'])[i % 5 + 1]::submission_status,
       CASE WHEN i % 17 = 0 THEN 'LATE' ELSE 'ON_TIME' END::submission_timing,
       now() - make_interval(secs => (850 - i) * 4),
       CASE WHEN i % 5 <> 0 THEN now() - make_interval(secs => (850 - i) * 4 - 10) END,
       CASE WHEN i % 5 IN (2, 3) THEN now() - make_interval(secs => (850 - i) * 4 - 20) END
FROM generate_series(1, 850) AS i;

-- 150 older rows that are no longer live: students 1..50 EXPIRED, 51..150 SUPERSEDED.
INSERT INTO submissions (id, assignment_id, student_id, idempotency_key, request_hash, sha256, size_bytes,
                         content_type, object_path, status, timing, committed_at, expired_at, superseded_at)
SELECT gen_random_uuid(),
       '00000000-0000-0000-0003-000000000001',
       ('00000000-0000-0000-0001-' || lpad(i::text, 12, '0'))::uuid,
       gen_random_uuid(), repeat('e', 64), repeat('b', 64), 500 + i, 'application/pdf',
       'a/old/' || i,
       (CASE WHEN i <= 50 THEN 'EXPIRED' ELSE 'SUPERSEDED' END)::submission_status,
       'ON_TIME', now() - interval '2 hours',
       CASE WHEN i <= 50 THEN now() - interval '90 minutes' END,
       CASE WHEN i > 50 THEN now() - interval '90 minutes' END
FROM generate_series(1, 150) AS i;

-- One VERIFY job per submission that reached UPLOADED: PENDING for UPLOADED, DONE for VERIFIED.
-- A few FAILED ones come from the REJECTED rows.
INSERT INTO jobs (type, submission_id, status, run_after)
SELECT 'VERIFY', s.id,
       (CASE s.status WHEN 'UPLOADED' THEN 'PENDING' WHEN 'VERIFIED' THEN 'DONE' ELSE 'FAILED' END)::job_status,
       now() - interval '1 minute'
FROM submissions s
WHERE s.status IN ('UPLOADED', 'VERIFIED', 'REJECTED_HASH_MISMATCH');

ANALYZE;
