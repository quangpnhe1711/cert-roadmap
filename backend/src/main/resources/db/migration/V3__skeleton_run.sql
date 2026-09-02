-- S0 Walking Skeleton only. This table is removed in slice S1 once real
-- domain tables exist; it exists to prove the end-to-end path.

CREATE TABLE skeleton_run (
    id            UUID         PRIMARY KEY,
    plan_id       UUID         NOT NULL,
    topic         TEXT         NOT NULL,
    status        TEXT         NOT NULL,
    artifact_id   UUID         REFERENCES generated_artifact (id),
    failure_code  TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    CONSTRAINT skeleton_run_status_valid CHECK (
        status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')
    )
);
