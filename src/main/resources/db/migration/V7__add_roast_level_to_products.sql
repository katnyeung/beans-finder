-- Add roast_level column to coffee_products table
ALTER TABLE coffee_products
ADD COLUMN roast_level VARCHAR(50);

-- Add index for performance
CREATE INDEX idx_coffee_products_roast_level ON coffee_products(roast_level);

-- Update comment
COMMENT ON COLUMN coffee_products.roast_level IS 'Roast level: Light, Medium, Dark, Omni, or Unknown';
