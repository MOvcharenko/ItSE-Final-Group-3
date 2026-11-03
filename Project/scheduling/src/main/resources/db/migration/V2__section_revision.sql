-- Lets a re-imported .ics file update events instead of duplicating them (FR6.3): the event's SEQUENCE.
ALTER TABLE section ADD COLUMN revision INTEGER NOT NULL DEFAULT 0;
