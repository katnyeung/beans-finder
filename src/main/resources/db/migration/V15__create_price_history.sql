-- Price history table for tracking price changes over time
-- Records price snapshot on every crawl, even if price unchanged
CREATE TABLE IF NOT EXISTS price_history (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    brand_id BIGINT,                    -- Denormalized for competitive analysis
    origin VARCHAR(100),                -- Denormalized for fast aggregation by origin
    price DECIMAL(10, 2),
    currency VARCHAR(3) DEFAULT 'GBP',
    bag_size VARCHAR(50),
    price_per_100g DECIMAL(10, 2),
    recorded_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_price_history_product FOREIGN KEY (product_id)
        REFERENCES coffee_products(id) ON DELETE CASCADE
);

-- Primary queries: product history (for price chart on product detail)
CREATE INDEX idx_price_history_product_time ON price_history(product_id, recorded_at DESC);

-- Future analytics: origin trends (e.g., "Colombia price trends")
CREATE INDEX idx_price_history_origin ON price_history(origin, recorded_at DESC);

-- Future analytics: brand competitive analysis
CREATE INDEX idx_price_history_brand ON price_history(brand_id, recorded_at DESC);

-- Time-series queries
CREATE INDEX idx_price_history_recorded_at ON price_history(recorded_at DESC);

COMMENT ON TABLE price_history IS 'Stores price snapshots on every crawl for trend analysis and B2B reports';
COMMENT ON COLUMN price_history.origin IS 'Denormalized from product for fast GROUP BY aggregation';
COMMENT ON COLUMN price_history.brand_id IS 'Denormalized for competitive pricing analysis';
COMMENT ON COLUMN price_history.price_per_100g IS 'Normalized price for comparison across different bag sizes';
