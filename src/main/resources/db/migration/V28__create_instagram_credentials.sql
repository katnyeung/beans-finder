-- Instagram API credentials storage
CREATE TABLE IF NOT EXISTS instagram_credentials (
    id BIGSERIAL PRIMARY KEY,
    instagram_user_id VARCHAR(50) NOT NULL UNIQUE,
    username VARCHAR(100),
    access_token TEXT NOT NULL,
    token_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP
);

CREATE INDEX idx_instagram_credentials_user_id ON instagram_credentials(instagram_user_id);
