-- Planning. Correction A1: PlanUnit holds everything plan-specific so the shared
-- LearningUnit stays immutable, and only StudyDayItem is rewritten by a replan.

CREATE TABLE study_plan (
    id                        UUID         PRIMARY KEY,
    user_id                   UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    certification_version_id  UUID         NOT NULL REFERENCES certification_version (id),
    material_revision_id      UUID         REFERENCES material_revision (id),
    exam_date                 DATE         NOT NULL,
    start_date                DATE         NOT NULL,
    status                    TEXT         NOT NULL DEFAULT 'DRAFT',
    -- Minutes available per ISO day-of-week, e.g. {"MONDAY":240,...}
    daily_capacity            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    blocked_dates             JSONB        NOT NULL DEFAULT '[]'::jsonb,
    compression_mode          TEXT         NOT NULL DEFAULT 'NONE',
    effort_ratio              NUMERIC(6,3),
    mapping_version           TEXT,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    activated_at              TIMESTAMPTZ,
    completed_at              TIMESTAMPTZ,
    CONSTRAINT study_plan_status CHECK (status IN
        ('DRAFT', 'ANALYZING', 'READY_FOR_REVIEW', 'ACTIVE', 'COMPLETED', 'EXPIRED', 'ABANDONED')),
    CONSTRAINT study_plan_compression CHECK (compression_mode IN
        ('NONE', 'DROP_OPTIONAL', 'DROP_LOW', 'CONDENSE_MEDIUM', 'MINIMAL_REVIEW'))
);

-- One active plan per user, as scoped.
CREATE UNIQUE INDEX study_plan_one_active_per_user
    ON study_plan (user_id) WHERE status IN ('DRAFT', 'ANALYZING', 'READY_FOR_REVIEW', 'ACTIVE');

CREATE TABLE plan_unit (
    plan_id                  UUID     NOT NULL REFERENCES study_plan (id) ON DELETE CASCADE,
    learning_unit_id         UUID     NOT NULL REFERENCES learning_unit (id) ON DELETE CASCADE,
    order_index              INT      NOT NULL,
    effective_effort_minutes INT      NOT NULL,
    depth_flag               TEXT     NOT NULL DEFAULT 'FULL',
    marked_known             BOOLEAN  NOT NULL DEFAULT FALSE,
    resolved_relevance       TEXT     NOT NULL DEFAULT 'MEDIUM',
    dropped_reason           TEXT,
    status                   TEXT     NOT NULL DEFAULT 'PENDING',
    PRIMARY KEY (plan_id, learning_unit_id),
    CONSTRAINT plan_unit_depth CHECK (depth_flag IN ('FULL', 'CONDENSED')),
    CONSTRAINT plan_unit_status CHECK (status IN ('PENDING', 'DONE', 'DROPPED')),
    CONSTRAINT plan_unit_relevance CHECK (resolved_relevance IN
        ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'OPTIONAL'))
);

CREATE INDEX plan_unit_order_idx ON plan_unit (plan_id, order_index);

CREATE TABLE study_day (
    id                UUID         PRIMARY KEY,
    plan_id           UUID         NOT NULL REFERENCES study_plan (id) ON DELETE CASCADE,
    day_date          DATE         NOT NULL,
    day_index         INT          NOT NULL,
    capacity_minutes  INT          NOT NULL,
    kind              TEXT         NOT NULL DEFAULT 'CONTENT',
    status            TEXT         NOT NULL DEFAULT 'PLANNED',
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    CONSTRAINT study_day_unique UNIQUE (plan_id, day_date),
    CONSTRAINT study_day_kind CHECK (kind IN ('CONTENT', 'REVIEW')),
    CONSTRAINT study_day_status CHECK (status IN
        ('PLANNED', 'IN_PROGRESS', 'COMPLETED', 'SKIPPED', 'RESCHEDULED'))
);

CREATE INDEX study_day_plan_idx ON study_day (plan_id, day_date);

-- The only table a replan rewrites.
CREATE TABLE study_day_item (
    id                UUID   PRIMARY KEY,
    study_day_id      UUID   NOT NULL REFERENCES study_day (id) ON DELETE CASCADE,
    plan_id           UUID   NOT NULL REFERENCES study_plan (id) ON DELETE CASCADE,
    item_type         TEXT   NOT NULL,
    learning_unit_id  UUID   REFERENCES learning_unit (id) ON DELETE CASCADE,
    course_topic_id   UUID   REFERENCES course_topic (id) ON DELETE CASCADE,
    order_index       INT    NOT NULL,
    allotted_minutes  INT    NOT NULL,
    status            TEXT   NOT NULL DEFAULT 'PENDING',
    completed_at      TIMESTAMPTZ,
    CONSTRAINT study_day_item_type CHECK (item_type IN
        ('LEARNING_UNIT', 'REVIEW_BLOCK', 'FINAL_REVIEW_EXAM')),
    CONSTRAINT study_day_item_status CHECK (status IN
        ('PENDING', 'IN_PROGRESS', 'DONE', 'CARRIED_FORWARD'))
);

CREATE INDEX study_day_item_day_idx ON study_day_item (study_day_id, order_index);
CREATE INDEX study_day_item_plan_idx ON study_day_item (plan_id, status);

CREATE TABLE plan_adjustment (
    id                 UUID         PRIMARY KEY,
    plan_id            UUID         NOT NULL REFERENCES study_plan (id) ON DELETE CASCADE,
    trigger            TEXT         NOT NULL,
    reason_code        TEXT         NOT NULL,
    params             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    schedule_snapshot  JSONB,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT plan_adjustment_trigger CHECK (trigger IN
        ('INITIAL', 'DAY_COMPLETED', 'DAY_MISSED', 'WEAK_TOPIC',
         'CONSTRAINTS_CHANGED', 'KNOWN_UNITS_CHANGED'))
);

CREATE INDEX plan_adjustment_plan_idx ON plan_adjustment (plan_id, created_at DESC);

-- The S0 walking skeleton has served its purpose: the real domain now exists.
DROP TABLE IF EXISTS skeleton_run;

-- Now that study_plan exists, tie the AI budget to it.
DELETE FROM ai_call_ledger WHERE plan_id IS NOT NULL;
DELETE FROM budget_reservation;
DELETE FROM generated_artifact;
DELETE FROM plan_budget;

ALTER TABLE plan_budget
    ADD CONSTRAINT plan_budget_plan_fk
    FOREIGN KEY (plan_id) REFERENCES study_plan (id) ON DELETE CASCADE;
