-- arms A and B only write here; evidence-only table
CREATE TABLE baseline_submissions (
    id uuid PRIMARY KEY,
    assignment_id uuid NOT NULL REFERENCES assignments(id),
    student_id uuid NOT NULL REFERENCES users(id),
    idempotency_key uuid,
    object_path text NOT NULL,
    size_bytes bigint NOT NULL,
    submitted_at timestamptz NOT NULL,
    stamp_source text NOT NULL CHECK (stamp_source IN ('UPLOAD_COMPLETE', 'FINALIZE')),
    timing submission_timing NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_base_assignment ON baseline_submissions(assignment_id);
CREATE UNIQUE INDEX ux_base_key ON baseline_submissions(idempotency_key) WHERE idempotency_key IS NOT NULL;
