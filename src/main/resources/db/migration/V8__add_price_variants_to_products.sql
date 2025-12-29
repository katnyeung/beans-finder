-- Add price_variants column to store multiple size/price options as JSONB
-- Example: [{"size": "250g", "price": 10.00}, {"size": "1kg", "price": 35.00}]
ALTER TABLE coffee_products ADD COLUMN IF NOT EXISTS price_variants_json JSONB;
