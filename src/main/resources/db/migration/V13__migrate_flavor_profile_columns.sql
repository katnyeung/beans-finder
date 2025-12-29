-- Migration: Move data from *_json columns to correct columns and drop duplicates
-- This fixes column name mismatch between Hibernate (flavor_profile_json) and V12 migration (flavor_profile)

-- Step 1: Copy valid JSONB data from flavor_profile_json to flavor_profile
-- Check that it's a non-empty array using jsonb_array_length
UPDATE coffee_products
SET flavor_profile = flavor_profile_json
WHERE flavor_profile_json IS NOT NULL
  AND jsonb_typeof(flavor_profile_json) = 'array'
  AND jsonb_array_length(flavor_profile_json) > 0
  AND (flavor_profile IS NULL OR flavor_profile = '[]'::jsonb);

-- Step 2: Copy valid JSONB data from character_axes_json to character_axes
UPDATE coffee_products
SET character_axes = character_axes_json
WHERE character_axes_json IS NOT NULL
  AND jsonb_typeof(character_axes_json) = 'array'
  AND jsonb_array_length(character_axes_json) > 0
  AND (character_axes IS NULL OR character_axes = '[]'::jsonb);

-- Step 3: Drop the _json columns
ALTER TABLE coffee_products DROP COLUMN IF EXISTS flavor_profile_json;
ALTER TABLE coffee_products DROP COLUMN IF EXISTS character_axes_json;
