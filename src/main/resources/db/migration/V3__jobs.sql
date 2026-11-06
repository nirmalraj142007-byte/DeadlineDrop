CREATE TYPE job_type AS ENUM ('VERIFY');
CREATE TYPE job_status AS ENUM ('PENDING', 'DONE', 'FAILED');

CREATE TABLE jobs (
    id bigserial PRIMARY KEY,
    type job_type NOT NULL,
    submission_id uuid NOT NULL REFERENCES submissions(id),
    status job_status NOT NULL DEFAULT 'PENDING',
    attempts int NOT NULL DEFAULT 0,
    run_after timestamptz NOT NULL DEFAULT now(),
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (type, submission_id)
);

CREATE INDEX ix_jobs_ready ON jobs(run_after) WHERE status = 'PENDING';
