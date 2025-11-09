-- Migration for geolocation features
-- Adds coordinates to brands and creates location cache table

-- Add geolocation fields to coffee_brands
ALTER TABLE coffee_brands
ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION,
ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION,
ADD COLUMN IF NOT EXISTS coordinates_validated BOOLEAN DEFAULT FALSE;

-- Create location_coordinates cache table
CREATE TABLE IF NOT EXISTS location_coordinates (
    id BIGSERIAL PRIMARY KEY,
    location_name VARCHAR(255) NOT NULL,
    country VARCHAR(100) NOT NULL,
    region VARCHAR(100),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    bounding_box TEXT, -- JSON string: {"minLat": x, "maxLat": y, "minLon": z, "maxLon": w}
    source VARCHAR(50), -- 'nominatim', 'manual', 'seeded'
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(location_name, country, region)
);

-- Create indexes for faster lookups
CREATE INDEX IF NOT EXISTS idx_location_country ON location_coordinates(country);
CREATE INDEX IF NOT EXISTS idx_location_country_region ON location_coordinates(country, region);
CREATE INDEX IF NOT EXISTS idx_brands_coordinates ON coffee_brands(latitude, longitude) WHERE latitude IS NOT NULL;

-- Comment on table
COMMENT ON TABLE location_coordinates IS 'Cache table for geocoded locations to avoid duplicate API calls';
COMMENT ON COLUMN coffee_brands.coordinates_validated IS 'Whether lat/lon have been verified via geocoding service';
