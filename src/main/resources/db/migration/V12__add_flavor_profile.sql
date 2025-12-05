-- 13-dimensional flavor profile system for coffee products
-- Part A: 9-dimensional flavor profile [0.0-1.0] for flavor presence
-- Indices: 0=fruity, 1=floral, 2=sweet, 3=nutty, 4=spices, 5=roasted, 6=green, 7=sour, 8=other

ALTER TABLE coffee_products
ADD COLUMN flavor_profile JSONB DEFAULT '[]'::jsonb;

-- Part B: 4-dimensional character axes [-1.0 to +1.0] for coffee character
-- Indices: 0=acidity (flat↔bright), 1=body (light↔full), 2=roast (light↔dark), 3=complexity (clean↔funky)

ALTER TABLE coffee_products
ADD COLUMN character_axes JSONB DEFAULT '[]'::jsonb;

-- GIN indexes for efficient JSONB operations
CREATE INDEX idx_products_flavor_profile ON coffee_products USING GIN (flavor_profile);
CREATE INDEX idx_products_character_axes ON coffee_products USING GIN (character_axes);

COMMENT ON COLUMN coffee_products.flavor_profile IS 'SCA category intensity vector [0.0-1.0]: [fruity, floral, sweet, nutty, spices, roasted, green, sour, other]';
COMMENT ON COLUMN coffee_products.character_axes IS 'Coffee character axes [-1.0 to +1.0]: [acidity, body, roast, complexity]';
