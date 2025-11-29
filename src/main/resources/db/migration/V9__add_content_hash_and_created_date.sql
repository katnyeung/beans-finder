-- Add content hash for change detection (SHA-256 = 64 chars hex)
ALTER TABLE coffee_products ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

-- Add created date (when product was first discovered)
ALTER TABLE coffee_products ADD COLUMN IF NOT EXISTS created_date TIMESTAMP;

-- Backfill created_date with last_update_date for existing records
UPDATE coffee_products SET created_date = last_update_date WHERE created_date IS NULL;

-- Add index for efficient new products query
CREATE INDEX IF NOT EXISTS idx_coffee_products_created_date ON coffee_products(created_date);

-- Add index for content hash lookup
CREATE INDEX IF NOT EXISTS idx_coffee_products_content_hash ON coffee_products(content_hash);
