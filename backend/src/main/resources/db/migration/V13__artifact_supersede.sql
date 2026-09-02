-- An artifact key identifies one piece of generated content, but its history is
-- not one row. Invalidating a bad generation has to leave the record of the
-- complaint in place while still allowing a replacement to be written, and a
-- global unique key on artifact_key made those two things mutually exclusive:
-- reuse was blocked and regeneration then failed on the constraint, so content a
-- learner reported as wrong could never be replaced.
--
-- The invariant that actually matters is narrower and is kept: at most one VALID
-- artifact per key, which is what the cache lookup relies on. Superseded and
-- invalidated rows accumulate as history.

ALTER TABLE generated_artifact DROP CONSTRAINT generated_artifact_key_unique;

CREATE UNIQUE INDEX generated_artifact_one_valid_per_key
    ON generated_artifact (artifact_key)
 WHERE cache_status = 'VALID';
