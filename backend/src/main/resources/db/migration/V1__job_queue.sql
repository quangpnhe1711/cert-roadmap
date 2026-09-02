-- Stage 2 correction A4: job queue with full recovery semantics.
-- status / attempts / available_at / lease / worker identity / dedupe key /
-- bounded retries / stale lease recovery / terminal failure state.

CREATE TABLE job (
    id                UUID         PRIMARY KEY,
    type              TEXT         NOT NULL,
    payload           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    dedupe_key        TEXT         NOT NULL,
    status            TEXT         NOT NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    max_attempts      INT          NOT NULL DEFAULT 3,
    available_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_at         TIMESTAMPTZ,
    lease_expires_at  TIMESTAMPTZ,
    worker_id         TEXT,
    last_error        TEXT,
    progress          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    CONSTRAINT job_dedupe_key_unique UNIQUE (dedupe_key),
    CONSTRAINT job_status_valid CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED')
    )
);

-- Claim path: only PENDING rows that are due.
CREATE INDEX job_claim_idx ON job (available_at)
    WHERE status = 'PENDING';

-- Stale lease sweep: only RUNNING rows carry a lease.
CREATE INDEX job_lease_idx ON job (lease_expires_at)
    WHERE status = 'RUNNING';
