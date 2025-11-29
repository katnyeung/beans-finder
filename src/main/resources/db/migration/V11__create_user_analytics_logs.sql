-- Create user analytics logs table for tracking user interactions
-- Used for generating sales reports for coffee brands

CREATE TABLE user_analytics_logs (
    id BIGSERIAL PRIMARY KEY,
    action_type VARCHAR(50) NOT NULL,
    product_id BIGINT,
    brand_id BIGINT,
    ip_hash VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    metadata JSONB DEFAULT '{}'::jsonb,

    FOREIGN KEY (product_id) REFERENCES coffee_products(id) ON DELETE SET NULL,
    FOREIGN KEY (brand_id) REFERENCES coffee_brands(id) ON DELETE SET NULL
);

-- Indexes for efficient querying
CREATE INDEX idx_analytics_created ON user_analytics_logs(created_at DESC);
CREATE INDEX idx_analytics_type ON user_analytics_logs(action_type);
CREATE INDEX idx_analytics_brand ON user_analytics_logs(brand_id);
CREATE INDEX idx_analytics_product ON user_analytics_logs(product_id);

COMMENT ON TABLE user_analytics_logs IS 'Tracks user interactions for sales reports: product views, seller clicks, chat Q&A';
COMMENT ON COLUMN user_analytics_logs.action_type IS 'Type: product_view, seller_click, chat_question, chat_answer';
COMMENT ON COLUMN user_analytics_logs.metadata IS 'Additional data: query text, seller URL, recommended product IDs, etc.';
