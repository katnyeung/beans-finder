-- Add update_requested flag to coffee_products for admin re-crawl queue
ALTER TABLE coffee_products ADD COLUMN IF NOT EXISTS update_requested BOOLEAN NOT NULL DEFAULT FALSE;

-- Create index for efficient querying of flagged products
CREATE INDEX IF NOT EXISTS idx_products_update_requested ON coffee_products(update_requested) WHERE update_requested = TRUE;
