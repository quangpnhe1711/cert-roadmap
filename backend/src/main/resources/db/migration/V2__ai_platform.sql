-- Stage 2 corrections A2 (artifact identity) and A3 (cost accounting order).

-- A3: atomic budget reservation. plan_id has no FK yet - study_plan arrives in
-- slice S1 and adds the constraint then.
CREATE TABLE plan_budget (
    plan_id               UUID         PRIMARY KEY,
    hard_cap_cents        INT          NOT NULL,
    soft_cap_cents        INT          NOT NULL,
    settled_cents         INT          NOT NULL DEFAULT 0,
    reserved_cents        INT          NOT NULL DEFAULT 0,
    soft_cap_breached_at  TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT plan_budget_non_negative CHECK (settled_cents >= 0 AND reserved_cents >= 0),
    CONSTRAINT plan_budget_caps CHECK (soft_cap_cents <= hard_cap_cents)
);

-- A3: reservations expire so a dead worker cannot leak budget forever.
CREATE TABLE budget_reservation (
    id                UUID         PRIMARY KEY,
    plan_id           UUID         NOT NULL REFERENCES plan_budget (plan_id) ON DELETE CASCADE,
    operation_id      TEXT         NOT NULL,
    estimated_cents   INT          NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    settled_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX budget_reservation_sweep_idx ON budget_reservation (expires_at)
    WHERE settled_at IS NULL;

-- A3: one row per PROVIDER ATTEMPT, including attempts later rejected by parse,
-- schema validation or domain validation. Those attempts consumed real tokens.
CREATE TABLE ai_call_ledger (
    id                UUID         PRIMARY KEY,
    plan_id           UUID,
    operation_id      TEXT         NOT NULL,
    attempt_no        INT          NOT NULL,
    model             TEXT         NOT NULL,
    tokens_in         INT          NOT NULL DEFAULT 0,
    tokens_out        INT          NOT NULL DEFAULT 0,
    cost_cents        INT          NOT NULL DEFAULT 0,
    latency_ms        INT          NOT NULL DEFAULT 0,
    outcome           TEXT         NOT NULL,
    artifact_key      TEXT,
    cache_hit         BOOLEAN      NOT NULL DEFAULT FALSE,
    reservation_id    UUID,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ai_call_outcome_valid CHECK (
        outcome IN ('SUCCESS', 'PARSE_FAIL', 'SCHEMA_FAIL', 'DOMAIN_FAIL',
                    'PROVIDER_ERROR', 'CACHE_HIT', 'BUDGET_DENIED')
    )
);

CREATE INDEX ai_call_ledger_plan_idx ON ai_call_ledger (plan_id, created_at);

-- A2: artifact identity = semantic inputs + generation version.
-- Never overwritten. A superseded artifact is still what a learner studied.
CREATE TABLE generated_artifact (
    id                       UUID         PRIMARY KEY,
    artifact_key             TEXT         NOT NULL,
    artifact_type            TEXT         NOT NULL,
    plan_id                  UUID,
    semantic_input_hash      TEXT         NOT NULL,
    generation_version_hash  TEXT         NOT NULL,
    cache_status             TEXT         NOT NULL,
    payload                  JSONB        NOT NULL,
    operation_id             TEXT         NOT NULL,
    prompt_version           TEXT         NOT NULL,
    output_schema_version    TEXT         NOT NULL,
    model                    TEXT         NOT NULL,
    model_config_version     TEXT         NOT NULL,
    tokens_in                INT          NOT NULL DEFAULT 0,
    tokens_out               INT          NOT NULL DEFAULT 0,
    cost_cents               INT          NOT NULL DEFAULT 0,
    superseded_by            UUID,
    invalidated_reason       TEXT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT generated_artifact_key_unique UNIQUE (artifact_key),
    CONSTRAINT generated_artifact_status_valid CHECK (
        cache_status IN ('VALID', 'SUPERSEDED', 'INVALIDATED')
    )
);

CREATE INDEX generated_artifact_lookup_idx ON generated_artifact (artifact_key)
    WHERE cache_status = 'VALID';
