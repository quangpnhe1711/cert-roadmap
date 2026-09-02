-- Material and everything derived from it.
--
-- Correction A1: derived content hangs off a MaterialRevision, and LearningUnit
-- knows nothing about any certification. Exam relevance lives in V7.

CREATE TABLE material (
    id                    UUID         PRIMARY KEY,
    user_id               UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    file_name             TEXT         NOT NULL,
    mime                  TEXT         NOT NULL,
    size_bytes            BIGINT       NOT NULL,
    storage_key           TEXT         NOT NULL,
    -- PDF rendition used by the in-app viewer; equals storage_key for PDFs.
    viewer_key            TEXT,
    source_content_hash   TEXT         NOT NULL,
    role                  TEXT         NOT NULL DEFAULT 'PRIMARY_COURSE',
    page_count            INT,
    ownership_attested_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- D9: scoped to the owner, not globally unique. Two people uploading the
    -- same course file is ordinary, not a conflict.
    CONSTRAINT material_owner_content_unique UNIQUE (user_id, source_content_hash),
    CONSTRAINT material_role CHECK (role IN
        ('PRIMARY_COURSE', 'OFFICIAL_REFERENCE', 'SUPPLEMENTARY', 'PERSONAL_NOTES', 'PRACTICE'))
);

-- A1: one row per processing pass. Re-uploading, editing section boundaries or
-- upgrading the extractor all create a new revision; older revisions stay
-- because completed study days still point at them.
CREATE TABLE material_revision (
    id                   UUID         PRIMARY KEY,
    material_id          UUID         NOT NULL REFERENCES material (id) ON DELETE CASCADE,
    revision_no          INT          NOT NULL,
    source_content_hash  TEXT         NOT NULL,
    extraction_version   TEXT         NOT NULL,
    structure_version    TEXT,
    structure_hash       TEXT,
    status               TEXT         NOT NULL,
    quality_flags        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    failure_reason       TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT material_revision_unique UNIQUE (material_id, revision_no),
    CONSTRAINT material_revision_status CHECK (status IN
        ('UPLOADED', 'EXTRACTING', 'EXTRACTED', 'STRUCTURING', 'READY', 'FAILED'))
);

CREATE TABLE material_page (
    id                    UUID     PRIMARY KEY,
    material_revision_id  UUID     NOT NULL REFERENCES material_revision (id) ON DELETE CASCADE,
    page_no               INT      NOT NULL,
    title_guess           TEXT,
    text                  TEXT     NOT NULL DEFAULT '',
    notes_text            TEXT,
    word_count            INT      NOT NULL DEFAULT 0,
    image_area_ratio      NUMERIC(4,3) NOT NULL DEFAULT 0,
    -- Correction P4: never pretend extracted text represents the whole slide.
    page_class            TEXT     NOT NULL DEFAULT 'TEXT_DOMINANT',
    has_significant_visual BOOLEAN NOT NULL DEFAULT FALSE,
    extraction_quality    NUMERIC(4,3) NOT NULL DEFAULT 1,
    CONSTRAINT material_page_unique UNIQUE (material_revision_id, page_no),
    CONSTRAINT material_page_class CHECK (page_class IN
        ('TEXT_DOMINANT', 'MIXED', 'IMAGE_DOMINANT'))
);

CREATE INDEX material_page_lookup_idx ON material_page (material_revision_id, page_no);

CREATE TABLE course_section (
    id                    UUID  PRIMARY KEY,
    material_revision_id  UUID  NOT NULL REFERENCES material_revision (id) ON DELETE CASCADE,
    parent_id             UUID  REFERENCES course_section (id) ON DELETE CASCADE,
    title                 TEXT  NOT NULL,
    order_index           INT   NOT NULL,
    depth                 INT   NOT NULL DEFAULT 0,
    page_start            INT   NOT NULL,
    page_end              INT   NOT NULL,
    CONSTRAINT course_section_range CHECK (page_end >= page_start)
);

CREATE INDEX course_section_revision_idx ON course_section (material_revision_id, order_index);

CREATE TABLE course_topic (
    id                UUID  PRIMARY KEY,
    course_section_id UUID  NOT NULL REFERENCES course_section (id) ON DELETE CASCADE,
    title             TEXT  NOT NULL,
    page_start        INT   NOT NULL,
    page_end          INT   NOT NULL,
    difficulty_tier   INT   NOT NULL DEFAULT 3,
    keywords          JSONB NOT NULL DEFAULT '[]'::jsonb,
    order_index       INT   NOT NULL DEFAULT 0,
    CONSTRAINT course_topic_tier CHECK (difficulty_tier BETWEEN 1 AND 5)
);

CREATE INDEX course_topic_section_idx ON course_topic (course_section_id);

-- A1: certification-agnostic. No relevance column here, deliberately.
CREATE TABLE learning_unit (
    id                    UUID   PRIMARY KEY,
    origin                TEXT   NOT NULL DEFAULT 'MATERIAL',
    material_revision_id  UUID   REFERENCES material_revision (id) ON DELETE CASCADE,
    course_section_id     UUID   REFERENCES course_section (id) ON DELETE SET NULL,
    sequence              INT    NOT NULL,
    title                 TEXT   NOT NULL,
    page_start            INT,
    page_end              INT,
    base_effort_minutes   INT    NOT NULL,
    content_hash          TEXT   NOT NULL,
    course_topic_ids      JSONB  NOT NULL DEFAULT '[]'::jsonb,
    -- Only for gap primers, which have no material behind them.
    certification_version_id UUID REFERENCES certification_version (id) ON DELETE CASCADE,
    task_statement_id     UUID   REFERENCES task_statement (id) ON DELETE CASCADE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT learning_unit_origin CHECK (origin IN ('MATERIAL', 'GAP_PRIMER')),
    CONSTRAINT learning_unit_material_shape CHECK (
        (origin = 'MATERIAL' AND material_revision_id IS NOT NULL
             AND page_start IS NOT NULL AND page_end IS NOT NULL)
     OR (origin = 'GAP_PRIMER' AND certification_version_id IS NOT NULL
             AND task_statement_id IS NOT NULL)
    )
);

CREATE INDEX learning_unit_revision_idx ON learning_unit (material_revision_id, sequence);

-- Deterministic inverted index. This is what makes glossary cross-referencing
-- exact and explainable without embeddings.
CREATE TABLE material_term_index (
    material_revision_id  UUID  NOT NULL REFERENCES material_revision (id) ON DELETE CASCADE,
    term                  TEXT  NOT NULL,
    page_no               INT   NOT NULL,
    occurrences           INT   NOT NULL DEFAULT 1,
    PRIMARY KEY (material_revision_id, term, page_no)
);
