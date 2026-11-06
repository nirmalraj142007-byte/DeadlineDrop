CREATE TYPE submission_status AS ENUM ('COMMITTED', 'UPLOADED', 'VERIFIED', 'REJECTED_HASH_MISMATCH', 'EXPIRED', 'SUPERSEDED');
CREATE TYPE submission_timing AS ENUM ('ON_TIME', 'LATE');
CREATE TYPE event_actor AS ENUM ('COMMIT', 'BROWSER_FINALIZE', 'RECONCILER', 'VERIFIER', 'NEW_COMMIT', 'RECEIPT');

-- No version column: every write is UPDATE ... WHERE id = ? AND status = ?; the status column is the guard.
CREATE TABLE submissions (
    id uuid PRIMARY KEY,
    assignment_id uuid NOT NULL REFERENCES assignments(id),
    student_id uuid NOT NULL REFERENCES users(id),
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    content_type text NOT NULL,
    object_path text NOT NULL UNIQUE,
    status submission_status NOT NULL,
    timing submission_timing NOT NULL,
    committed_at timestamptz NOT NULL,
    db_committed_at timestamptz NOT NULL DEFAULT now(),
    uploaded_at timestamptz,
    verified_at timestamptz,
    expired_at timestamptz,
    superseded_at timestamptz,
    superseded_by uuid REFERENCES submissions(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_sub_student_key ON submissions(student_id, idempotency_key);
CREATE UNIQUE INDEX ux_live_submission ON submissions(assignment_id, student_id) WHERE status NOT IN ('EXPIRED', 'SUPERSEDED');
CREATE INDEX ix_sub_assignment_status ON submissions(assignment_id, status);
CREATE INDEX ix_sub_reconcile ON submissions(status, committed_at) WHERE status IN ('COMMITTED', 'UPLOADED');

CREATE TABLE submission_events (
    id bigserial PRIMARY KEY,
    submission_id uuid NOT NULL REFERENCES submissions(id),
    from_status submission_status,
    to_status submission_status NOT NULL,
    actor event_actor NOT NULL,
    at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_events_sub ON submission_events(submission_id, at);
