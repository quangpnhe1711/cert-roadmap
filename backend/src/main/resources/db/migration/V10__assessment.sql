-- Assessment. Grading and mastery are deterministic; no LLM touches either.

CREATE TABLE question (
    id                    UUID         PRIMARY KEY,
    plan_id               UUID         NOT NULL REFERENCES study_plan (id) ON DELETE CASCADE,
    learning_unit_id      UUID         REFERENCES learning_unit (id) ON DELETE CASCADE,
    task_statement_id     UUID         NOT NULL REFERENCES task_statement (id) ON DELETE CASCADE,
    course_topic_id       UUID         REFERENCES course_topic (id) ON DELETE SET NULL,
    artifact_key          TEXT,
    question_type         TEXT         NOT NULL,
    stem                  TEXT         NOT NULL,
    options               JSONB        NOT NULL,
    correct_option_ids    JSONB        NOT NULL,
    explanation           TEXT         NOT NULL,
    distractor_rationales JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- Hard requirement: a question with no resolvable source is discarded at
    -- generation time and never reaches a learner.
    source_span           TEXT         NOT NULL,
    source_page_start     INT,
    source_page_end       INT,
    difficulty            INT          NOT NULL DEFAULT 3,
    prompt_version        TEXT,
    validation_status     TEXT         NOT NULL DEFAULT 'VALID',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT question_type_valid CHECK (question_type IN
        ('SINGLE_CHOICE', 'MULTIPLE_RESPONSE', 'SCENARIO_SINGLE')),
    CONSTRAINT question_validation CHECK (validation_status IN ('VALID', 'REJECTED', 'FLAGGED')),
    CONSTRAINT question_source_present CHECK (length(trim(source_span)) > 0),
    CONSTRAINT question_difficulty CHECK (difficulty BETWEEN 1 AND 5)
);

CREATE INDEX question_bank_idx ON question (plan_id, course_topic_id) WHERE validation_status = 'VALID';
CREATE INDEX question_unit_idx ON question (plan_id, learning_unit_id);
CREATE INDEX question_task_idx ON question (plan_id, task_statement_id);

CREATE TABLE quiz_attempt (
    id            UUID         PRIMARY KEY,
    plan_id       UUID         NOT NULL REFERENCES study_plan (id) ON DELETE CASCADE,
    study_day_id  UUID         REFERENCES study_day (id) ON DELETE SET NULL,
    kind          TEXT         NOT NULL,
    status        TEXT         NOT NULL DEFAULT 'IN_PROGRESS',
    score_raw     INT,
    score_total   INT,
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    submitted_at  TIMESTAMPTZ,
    CONSTRAINT quiz_attempt_kind CHECK (kind IN ('DAILY', 'WARMUP', 'FINAL_REVIEW')),
    CONSTRAINT quiz_attempt_status CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'GRADED'))
);

CREATE INDEX quiz_attempt_plan_idx ON quiz_attempt (plan_id, started_at DESC);

CREATE TABLE attempt_question (
    attempt_id          UUID     NOT NULL REFERENCES quiz_attempt (id) ON DELETE CASCADE,
    question_id         UUID     NOT NULL REFERENCES question (id) ON DELETE CASCADE,
    order_index         INT      NOT NULL,
    selected_option_ids JSONB    NOT NULL DEFAULT '[]'::jsonb,
    is_correct          BOOLEAN,
    answered_at         TIMESTAMPTZ,
    PRIMARY KEY (attempt_id, question_id)
);

-- A cache, fully recomputable from attempt_question. Never authoritative.
CREATE TABLE topic_mastery (
    plan_id           UUID         NOT NULL REFERENCES study_plan (id) ON DELETE CASCADE,
    course_topic_id   UUID         NOT NULL REFERENCES course_topic (id) ON DELETE CASCADE,
    correct_count     INT          NOT NULL DEFAULT 0,
    total_count       INT          NOT NULL DEFAULT 0,
    mistake_count     INT          NOT NULL DEFAULT 0,
    sessions_count    INT          NOT NULL DEFAULT 0,
    first_session_on  DATE,
    last_session_on   DATE,
    last_assessed_at  TIMESTAMPTZ,
    status            TEXT         NOT NULL DEFAULT 'NOT_STARTED',
    PRIMARY KEY (plan_id, course_topic_id),
    CONSTRAINT topic_mastery_status CHECK (status IN
        ('NOT_STARTED', 'LEARNING', 'REVIEW', 'MASTERED'))
);

-- Tier-one weak signal: a MUST_KNOW concept missed even once is re-asked in the
-- next warm-up. Waiting for three answers would never fire in week one.
CREATE TABLE warmup_queue (
    id           UUID         PRIMARY KEY,
    plan_id      UUID         NOT NULL REFERENCES study_plan (id) ON DELETE CASCADE,
    question_id  UUID         NOT NULL REFERENCES question (id) ON DELETE CASCADE,
    queued_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    consumed_at  TIMESTAMPTZ,
    CONSTRAINT warmup_queue_unique UNIQUE (plan_id, question_id)
);

CREATE INDEX warmup_queue_pending_idx ON warmup_queue (plan_id, queued_at)
    WHERE consumed_at IS NULL;
