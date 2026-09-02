-- Learning Pack: typed content blocks, never an HTML blob, so any client
-- (including a future mobile app) renders the same contract.

CREATE TABLE learning_pack (
    id                        UUID         PRIMARY KEY,
    -- Correction A2: full identity. Never overwritten.
    artifact_key              TEXT         NOT NULL,
    learning_unit_id          UUID         NOT NULL REFERENCES learning_unit (id) ON DELETE CASCADE,
    certification_version_id  UUID         NOT NULL REFERENCES certification_version (id) ON DELETE CASCADE,
    plan_id                   UUID         REFERENCES study_plan (id) ON DELETE SET NULL,
    cache_status              TEXT         NOT NULL DEFAULT 'VALID',
    depth_flag                TEXT         NOT NULL DEFAULT 'FULL',
    model                     TEXT         NOT NULL,
    prompt_version            TEXT         NOT NULL,
    output_schema_version     TEXT         NOT NULL,
    model_config_version      TEXT         NOT NULL,
    tokens_in                 INT          NOT NULL DEFAULT 0,
    tokens_out                INT          NOT NULL DEFAULT 0,
    cost_cents                INT          NOT NULL DEFAULT 0,
    review_status             TEXT         NOT NULL DEFAULT 'UNREVIEWED',
    superseded_by             UUID,
    invalidated_reason        TEXT,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT learning_pack_key_unique UNIQUE (artifact_key),
    CONSTRAINT learning_pack_cache_status CHECK (cache_status IN
        ('VALID', 'SUPERSEDED', 'INVALIDATED'))
);

CREATE INDEX learning_pack_unit_idx
    ON learning_pack (learning_unit_id, certification_version_id) WHERE cache_status = 'VALID';

CREATE TABLE content_block (
    id                          UUID   PRIMARY KEY,
    learning_pack_id            UUID   NOT NULL REFERENCES learning_pack (id) ON DELETE CASCADE,
    order_index                 INT    NOT NULL,
    block_type                  TEXT   NOT NULL,
    payload                     JSONB  NOT NULL,
    -- FROM_MATERIAL blocks must carry a page range; AI_SUPPLEMENT blocks are
    -- background the model added and are rendered differently.
    origin                      TEXT   NOT NULL DEFAULT 'FROM_MATERIAL',
    exam_relevance              TEXT,
    source_material_revision_id UUID   REFERENCES material_revision (id) ON DELETE SET NULL,
    source_page_start           INT,
    source_page_end             INT,
    source_span                 TEXT,
    CONSTRAINT content_block_type CHECK (block_type IN
        ('overview', 'concept', 'exam_tip', 'keyword', 'comparison', 'example',
         'warning', 'relationship', 'source_reference', 'check_understanding')),
    CONSTRAINT content_block_origin CHECK (origin IN ('FROM_MATERIAL', 'AI_SUPPLEMENT')),
    CONSTRAINT content_block_relevance CHECK (exam_relevance IS NULL OR exam_relevance IN
        ('MUST_KNOW', 'SHOULD_KNOW', 'GOOD_TO_KNOW', 'NOT_REQUIRED')),
    CONSTRAINT content_block_material_traceable CHECK (
        origin <> 'FROM_MATERIAL' OR source_page_start IS NOT NULL
    )
);

CREATE INDEX content_block_pack_idx ON content_block (learning_pack_id, order_index);

-- Explain a term once per plan; later mentions link back.
CREATE TABLE glossary_entry (
    id              UUID         PRIMARY KEY,
    plan_id         UUID         NOT NULL REFERENCES study_plan (id) ON DELETE CASCADE,
    term            TEXT         NOT NULL,
    first_pack_id   UUID         REFERENCES learning_pack (id) ON DELETE SET NULL,
    simple_text     TEXT,
    technical_text  TEXT,
    example_text    TEXT,
    exam_note       TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- D9: scoped to the plan, not global.
    CONSTRAINT glossary_entry_unique UNIQUE (plan_id, term)
);

CREATE TABLE content_flag (
    id                        UUID         PRIMARY KEY,
    user_id                   UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    block_id                  UUID         REFERENCES content_block (id) ON DELETE CASCADE,
    question_id               UUID,
    reason                    TEXT         NOT NULL,
    note                      TEXT,
    invalidated_artifact_key  TEXT,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at               TIMESTAMPTZ
);

CREATE INDEX content_flag_open_idx ON content_flag (created_at DESC) WHERE resolved_at IS NULL;
