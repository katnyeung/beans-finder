-- Add soft delete support to coffee_products
-- Products are flagged as deleted instead of being removed from the database
-- This preserves price history and allows recovery if product returns

-- Add soft delete column (null = active, timestamp = deleted)
ALTER TABLE coffee_products ADD COLUMN deleted_at TIMESTAMP;

-- Index for efficient filtering of active products
CREATE INDEX idx_coffee_products_deleted_at ON coffee_products(deleted_at);

-- Composite index for common queries (find active products by brand)
CREATE INDEX idx_coffee_products_brand_deleted ON coffee_products(brand_id, deleted_at);
