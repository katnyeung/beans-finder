-- Weekly digest content combining news and market data
CREATE TABLE IF NOT EXISTS weekly_digests (
    id BIGSERIAL PRIMARY KEY,
    week_start DATE NOT NULL,
    week_end DATE NOT NULL,
    narrative TEXT NOT NULL,
    metrics JSONB NOT NULL,
    news_article_ids JSONB,
    generated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    generation_cost DECIMAL(6,4),
    status VARCHAR(20) DEFAULT 'draft',
    CONSTRAINT uk_weekly_digests_week UNIQUE (week_start)
);

CREATE INDEX IF NOT EXISTS idx_weekly_digests_week ON weekly_digests(week_start DESC);
CREATE INDEX IF NOT EXISTS idx_weekly_digests_status ON weekly_digests(status);

COMMENT ON TABLE weekly_digests IS 'Weekly market narrative combining news and Graphee data';
COMMENT ON COLUMN weekly_digests.narrative IS 'LLM-generated market commentary in news-style paragraphs';
COMMENT ON COLUMN weekly_digests.metrics IS 'JSON object with query results used in narrative';
COMMENT ON COLUMN weekly_digests.news_article_ids IS 'JSON array of coffee_news IDs used in this digest';
COMMENT ON COLUMN weekly_digests.status IS 'draft or published';
