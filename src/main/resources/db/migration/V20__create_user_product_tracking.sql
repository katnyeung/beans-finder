-- User product tracking (love, bought, want, dislike + ratings)
CREATE TABLE user_product_tracking (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES coffee_products(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,  -- 'love', 'bought', 'want', 'dislike'
    rating SMALLINT CHECK (rating >= 1 AND rating <= 5),
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, product_id)
);

CREATE INDEX idx_user_tracking_user ON user_product_tracking(user_id);
CREATE INDEX idx_user_tracking_product ON user_product_tracking(product_id);
CREATE INDEX idx_user_tracking_status ON user_product_tracking(status);
