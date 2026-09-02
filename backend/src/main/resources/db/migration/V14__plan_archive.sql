-- A learner has one plan at a time, not one plan ever.
--
-- The partial unique index below is unchanged and stays the invariant: at most
-- one plan per user is in an unfinished state. What was missing was somewhere
-- for a finished plan to go that is neither "still current" nor "discarded", so
-- the history a learner accumulates over several certifications had no home and
-- the only way to start a second plan was to lose the first.
--
-- ABANDONED already existed and keeps its meaning: a draft the learner walked
-- away from, replaced by starting over. It is machine-generated and hidden.
-- ARCHIVED is the learner's own decision to put a plan away, and it stays
-- visible in their list.

ALTER TABLE study_plan DROP CONSTRAINT study_plan_status;

ALTER TABLE study_plan ADD CONSTRAINT study_plan_status CHECK (status IN
    ('DRAFT', 'ANALYZING', 'READY_FOR_REVIEW', 'ACTIVE',
     'COMPLETED', 'EXPIRED', 'ARCHIVED', 'ABANDONED'));

-- Ordering the plan list by "when did this matter to the learner" needs a
-- timestamp that survives archiving; created_at alone puts a plan finished
-- yesterday below one started last year.
ALTER TABLE study_plan ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS study_plan_user_idx ON study_plan (user_id, created_at DESC);
