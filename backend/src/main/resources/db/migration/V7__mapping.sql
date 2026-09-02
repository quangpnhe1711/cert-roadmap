-- Correction A1/A2: the certification-dependent layer.
--
-- Keyed by (learning unit, certification version, mapping version) so the same
-- material can be mapped against another certification without rebuilding any
-- learning unit, and so a mapping algorithm change produces new rows rather
-- than silently reusing old ones.

CREATE TABLE unit_exam_mapping (
    id                        UUID         PRIMARY KEY,
    learning_unit_id          UUID         NOT NULL REFERENCES learning_unit (id) ON DELETE CASCADE,
    certification_version_id  UUID         NOT NULL REFERENCES certification_version (id) ON DELETE CASCADE,
    task_statement_id         UUID         NOT NULL REFERENCES task_statement (id) ON DELETE CASCADE,
    mapping_version           TEXT         NOT NULL,
    relevance                 TEXT         NOT NULL,
    confidence                NUMERIC(3,2) NOT NULL,
    rationale                 TEXT,
    method                    TEXT         NOT NULL DEFAULT 'AI_EXTRACTED',
    artifact_key              TEXT,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT unit_exam_mapping_unique
        UNIQUE (learning_unit_id, certification_version_id, task_statement_id, mapping_version),
    CONSTRAINT unit_exam_mapping_relevance CHECK (relevance IN
        ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'OPTIONAL')),
    CONSTRAINT unit_exam_mapping_confidence CHECK (confidence BETWEEN 0 AND 1)
);

CREATE INDEX unit_exam_mapping_unit_idx
    ON unit_exam_mapping (learning_unit_id, certification_version_id);
CREATE INDEX unit_exam_mapping_task_idx
    ON unit_exam_mapping (certification_version_id, task_statement_id);

-- Coverage is a deterministic set operation over the mappings above. It is an
-- MVP capability because the planner needs to know which exam tasks have no
-- material; the matrix UI on top of it is the part that can be trimmed.
CREATE TABLE coverage_assessment (
    id                        UUID         PRIMARY KEY,
    material_revision_id      UUID         NOT NULL REFERENCES material_revision (id) ON DELETE CASCADE,
    certification_version_id  UUID         NOT NULL REFERENCES certification_version (id) ON DELETE CASCADE,
    mapping_version           TEXT         NOT NULL,
    task_statement_id         UUID         NOT NULL REFERENCES task_statement (id) ON DELETE CASCADE,
    status                    TEXT         NOT NULL,
    evidence                  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    computed_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT coverage_assessment_unique
        UNIQUE (material_revision_id, certification_version_id, mapping_version, task_statement_id),
    CONSTRAINT coverage_assessment_status CHECK (status IN
        ('COVERED', 'PARTIAL', 'NOT_COVERED'))
);

CREATE INDEX coverage_assessment_lookup_idx
    ON coverage_assessment (material_revision_id, certification_version_id, mapping_version);
