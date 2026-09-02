-- Certification catalog. Curated, shared across users, read-only at runtime.
-- Generic by construction: nothing here names AWS. AIF-C01 is seed data (V11),
-- not code.

CREATE TABLE certification (
    id        UUID  PRIMARY KEY,
    provider  TEXT  NOT NULL,
    name      TEXT  NOT NULL,
    slug      TEXT  NOT NULL,
    CONSTRAINT certification_slug_unique UNIQUE (slug)
);

CREATE TABLE certification_version (
    id                UUID         PRIMARY KEY,
    certification_id  UUID         NOT NULL REFERENCES certification (id) ON DELETE CASCADE,
    exam_code         TEXT         NOT NULL,
    version_label     TEXT         NOT NULL,
    effective_from    DATE         NOT NULL,
    retired_at        DATE,
    official_url      TEXT         NOT NULL,
    exam_guide_url    TEXT,
    duration_minutes  INT,
    question_count    INT,
    -- Nullable on purpose. When a provider does not publish a passing score the
    -- UI says so; nothing is allowed to guess it.
    passing_score     INT,
    status            TEXT         NOT NULL DEFAULT 'PUBLISHED',
    content_hash      TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT certification_version_unique UNIQUE (certification_id, exam_code, version_label),
    CONSTRAINT certification_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED'))
);

CREATE TABLE exam_domain (
    id                        UUID  PRIMARY KEY,
    certification_version_id  UUID  NOT NULL REFERENCES certification_version (id) ON DELETE CASCADE,
    code                      TEXT  NOT NULL,
    title                     TEXT  NOT NULL,
    weight_percent            INT   NOT NULL,
    order_index               INT   NOT NULL,
    CONSTRAINT exam_domain_unique UNIQUE (certification_version_id, code),
    CONSTRAINT exam_domain_weight CHECK (weight_percent BETWEEN 0 AND 100)
);

CREATE TABLE task_statement (
    id              UUID  PRIMARY KEY,
    exam_domain_id  UUID  NOT NULL REFERENCES exam_domain (id) ON DELETE CASCADE,
    code            TEXT  NOT NULL,
    title           TEXT  NOT NULL,
    order_index     INT   NOT NULL,
    CONSTRAINT task_statement_unique UNIQUE (exam_domain_id, code)
);

CREATE TABLE knowledge_item (
    id                 UUID     PRIMARY KEY,
    task_statement_id  UUID     NOT NULL REFERENCES task_statement (id) ON DELETE CASCADE,
    text               TEXT     NOT NULL,
    -- Drives the "did the lesson cover everything critical" validator.
    is_must_know       BOOLEAN  NOT NULL DEFAULT FALSE,
    order_index        INT      NOT NULL DEFAULT 0
);

CREATE INDEX knowledge_item_task_idx ON knowledge_item (task_statement_id);

CREATE TABLE official_resource (
    id                        UUID  PRIMARY KEY,
    certification_version_id  UUID  NOT NULL REFERENCES certification_version (id) ON DELETE CASCADE,
    task_statement_id         UUID  REFERENCES task_statement (id) ON DELETE CASCADE,
    title                     TEXT  NOT NULL,
    url                       TEXT  NOT NULL,
    resource_type             TEXT  NOT NULL DEFAULT 'DOCUMENTATION'
);

CREATE INDEX official_resource_task_idx ON official_resource (task_statement_id);

-- Polymorphic provenance for every factual catalog item.
CREATE TABLE source_ref (
    id            UUID         PRIMARY KEY,
    entity_type   TEXT         NOT NULL,
    entity_id     UUID         NOT NULL,
    field         TEXT,
    url           TEXT         NOT NULL,
    doc_title     TEXT,
    retrieved_at  TIMESTAMPTZ  NOT NULL,
    content_hash  TEXT,
    method        TEXT         NOT NULL,
    confidence    NUMERIC(3,2),
    verified_by   TEXT,
    verified_at   TIMESTAMPTZ,
    CONSTRAINT source_ref_method CHECK (method IN ('MANUAL', 'AI_EXTRACTED', 'AI_INFERRED'))
);

CREATE INDEX source_ref_entity_idx ON source_ref (entity_type, entity_id);
