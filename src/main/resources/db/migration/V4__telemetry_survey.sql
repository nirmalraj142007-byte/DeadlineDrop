-- no FK to users, by design (anonymised)
CREATE TABLE telemetry_events (
    id bigserial PRIMARY KEY,
    anon_id char(64) NOT NULL,
    assignment_id uuid NOT NULL,
    consent_version text NOT NULL,
    upload_mbps numeric(8,3),
    size_bytes bigint,
    click_offset_ms int,
    hash_ms int,
    network_type text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (anon_id, assignment_id)
);

CREATE TABLE survey_responses (
    id bigserial PRIMARY KEY,
    anon_id char(64) NOT NULL,
    assignment_id uuid NOT NULL,
    marked_late_before text CHECK (marked_late_before IN ('YES', 'NO', 'UNSURE')),
    usual_network text CHECK (usual_network IN ('HOSTEL_WIFI', 'MOBILE_DATA', 'HOME_BROADBAND', 'CAMPUS_LAN', 'OTHER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (anon_id, assignment_id)
);
