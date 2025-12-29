-- Coffee news articles from RSS feeds with LLM-extracted metadata
CREATE TABLE IF NOT EXISTS coffee_news (
    id BIGSERIAL PRIMARY KEY,
    source VARCHAR(100) NOT NULL,
    title VARCHAR(500) NOT NULL,
    link VARCHAR(1000) NOT NULL,
    published_at TIMESTAMP,
    summary VARCHAR(500),
    themes JSONB,
    sentiment VARCHAR(20),
    relevance_score DECIMAL(3,2),
    fetched_at TIMESTAMP NOT NULL DEFAULT NOW(),
    content_hash VARCHAR(64),
    CONSTRAINT uk_coffee_news_link UNIQUE (link)
);

CREATE INDEX IF NOT EXISTS idx_coffee_news_published ON coffee_news(published_at DESC);
CREATE INDEX IF NOT EXISTS idx_coffee_news_source ON coffee_news(source, published_at DESC);
CREATE INDEX IF NOT EXISTS idx_coffee_news_themes ON coffee_news USING GIN (themes);

COMMENT ON TABLE coffee_news IS 'Coffee industry news from RSS feeds with LLM-extracted themes';
COMMENT ON COLUMN coffee_news.themes IS 'JSON array of themes like ["price", "origin:Ethiopia", "sustainability"]';
COMMENT ON COLUMN coffee_news.relevance_score IS 'LLM-assigned relevance score 0.00-1.00 for specialty coffee';
