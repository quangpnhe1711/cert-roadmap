-- The capacity reality check compares a learner's available time against the
-- effort a certification typically needs. That range is a property of the
-- certification, so it belongs in the catalog rather than hard-coded in a
-- service where it would silently apply to every future certification.

ALTER TABLE certification_version
    ADD COLUMN typical_effort_low_minutes  INT NOT NULL DEFAULT 2400,
    ADD COLUMN typical_effort_high_minutes INT NOT NULL DEFAULT 3600;

COMMENT ON COLUMN certification_version.typical_effort_low_minutes IS
    'Lower bound of typical preparation effort, in minutes, for a learner with some background.';

-- AIF-C01 is a foundational exam: roughly 40-60 hours is the commonly reported range.
UPDATE certification_version
   SET typical_effort_low_minutes = 2400,
       typical_effort_high_minutes = 3600
 WHERE exam_code = 'AIF-C01';
