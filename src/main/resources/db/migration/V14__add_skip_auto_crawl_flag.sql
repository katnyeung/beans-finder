-- Add skip_auto_crawl flag to coffee_brands
-- Brands with this flag set will be skipped in batch crawl operations
-- They can still be crawled manually via the crawl-from-sitemap endpoint

ALTER TABLE coffee_brands ADD COLUMN IF NOT EXISTS skip_auto_crawl BOOLEAN NOT NULL DEFAULT FALSE;

-- Set the flag for brewed.online (large catalog)
UPDATE coffee_brands SET skip_auto_crawl = TRUE WHERE id = 55;
