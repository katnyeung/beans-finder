-- Fix bounding_box column type from bytea to TEXT
-- This issue occurred because Hibernate auto-created the column incorrectly

ALTER TABLE location_coordinates
ALTER COLUMN bounding_box TYPE TEXT USING bounding_box::text;
