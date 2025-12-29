-- Allow multiple digest variants per week for comparison before publishing
-- Drop the unique constraint on week_start
ALTER TABLE weekly_digests DROP CONSTRAINT IF EXISTS uk_weekly_digests_week;

-- Add version column to track multiple drafts
ALTER TABLE weekly_digests ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 1;

-- Create new unique constraint on week_start + status (only one published per week)
-- This allows multiple drafts but only one published digest per week
CREATE UNIQUE INDEX IF NOT EXISTS uk_weekly_digests_published
ON weekly_digests(week_start) WHERE status = 'published';

COMMENT ON COLUMN weekly_digests.version IS 'Draft version number for comparison';
