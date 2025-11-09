-- Add detailed location fields to coffee_brands table for precise geocoding
-- This allows brands to be mapped to specific shop addresses instead of just country center

ALTER TABLE coffee_brands
ADD COLUMN city VARCHAR(100),
ADD COLUMN address VARCHAR(255),
ADD COLUMN postcode VARCHAR(20);

-- Add index for geocoding lookups
CREATE INDEX idx_coffee_brands_city ON coffee_brands(city);
CREATE INDEX idx_coffee_brands_postcode ON coffee_brands(postcode);

-- Comments for documentation
COMMENT ON COLUMN coffee_brands.city IS 'City/town where the coffee shop/roastery is located';
COMMENT ON COLUMN coffee_brands.address IS 'Full street address of the shop/roastery';
COMMENT ON COLUMN coffee_brands.postcode IS 'Postal/ZIP code';
