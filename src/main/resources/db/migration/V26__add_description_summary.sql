-- Add description_summary column for copyright-compliant condensed descriptions
ALTER TABLE coffee_products ADD COLUMN IF NOT EXISTS description_summary TEXT;
