-- Brand suggestions table for user-submitted brand recommendations
-- Reviewed manually before enriching and moving to coffee_brands

CREATE TABLE brand_suggestions (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    website_url VARCHAR(500) NOT NULL,
    submitted_by_ip VARCHAR(45),
    status VARCHAR(50) DEFAULT 'pending',
    reviewer_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    reviewed_at TIMESTAMP
);

CREATE INDEX idx_brand_suggestions_status ON brand_suggestions(status);
CREATE INDEX idx_brand_suggestions_created_at ON brand_suggestions(created_at DESC);
