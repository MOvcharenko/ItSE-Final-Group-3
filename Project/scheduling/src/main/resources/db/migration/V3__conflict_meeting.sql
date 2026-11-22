-- The meetings a conflict is about, so a timetable flags the meetings that clash and not every meeting of the
-- section. A conflict with no rows here is about its sections as a whole (capacity exceeded), as are conflicts
-- detected before this table existed, until the next check of their term refills them.
CREATE TABLE conflict_meeting (
    conflict_id   VARCHAR(36) NOT NULL REFERENCES conflict (id) ON DELETE CASCADE,
    section_id    VARCHAR(36) NOT NULL REFERENCES section (id)  ON DELETE CASCADE,
    pattern       VARCHAR(10) NOT NULL,
    day_of_week   VARCHAR(10),                          -- WEEKLY only
    meeting_date  DATE,                                 -- ONE_TIME / EXAM only
    start_time    TIME        NOT NULL,
    end_time      TIME        NOT NULL,
    CONSTRAINT ck_conflict_meeting_pattern CHECK (pattern IN ('WEEKLY', 'ONE_TIME', 'EXAM')),
    CONSTRAINT ck_conflict_meeting_day CHECK (day_of_week IS NULL OR day_of_week IN
        ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'))
);
CREATE INDEX ix_conflict_meeting_conflict ON conflict_meeting (conflict_id);
