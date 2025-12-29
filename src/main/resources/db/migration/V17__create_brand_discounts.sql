-- Brand discounts table (one-to-many with coffee_brands)
-- Stores promotions extracted from brand homepages during crawling

CREATE TABLE brand_discounts (
    id BIGSERIAL PRIMARY KEY,
    brand_id BIGINT NOT NULL REFERENCES coffee_brands(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    discount_code VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_brand_discounts_brand_id ON brand_discounts(brand_id);

-- Store content hash per brand to skip LLM if homepage unchanged
ALTER TABLE coffee_brands ADD COLUMN IF NOT EXISTS discount_content_hash VARCHAR(64);
