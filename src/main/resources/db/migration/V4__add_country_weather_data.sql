-- Migration V4: Add country weather data table for historical climate tracking
-- Purpose: Store monthly weather data (2020-2025) for coffee-growing countries
-- Date: 2025-11-08

CREATE TABLE country_weather_data (
    id BIGSERIAL PRIMARY KEY,
    country_name VARCHAR(100) NOT NULL,
    country_code VARCHAR(2) NOT NULL,
    year INTEGER NOT NULL,
    month INTEGER NOT NULL,
    avg_temperature DOUBLE PRECISION,
    total_rainfall DOUBLE PRECISION,
    avg_soil_moisture DOUBLE PRECISION,
    avg_solar_radiation DOUBLE PRECISION,
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source VARCHAR(50) DEFAULT 'open-meteo',
    CONSTRAINT unique_country_year_month UNIQUE (country_code, year, month),
    CONSTRAINT valid_month CHECK (month >= 1 AND month <= 12),
    CONSTRAINT valid_year CHECK (year >= 2020 AND year <= 2030)
);

-- Create index for fast country lookups
CREATE INDEX idx_country_year_month ON country_weather_data (country_code, year, month);

-- Create index for time-series queries
CREATE INDEX idx_year_month ON country_weather_data (year, month);

-- Comments for documentation
COMMENT ON TABLE country_weather_data IS 'Historical weather data for coffee-growing countries (monthly aggregates from Open-Meteo API)';
COMMENT ON COLUMN country_weather_data.country_code IS 'ISO 3166-1 alpha-2 country code (e.g., CO, ET, BR)';
COMMENT ON COLUMN country_weather_data.avg_temperature IS 'Average monthly temperature in degrees Celsius';
COMMENT ON COLUMN country_weather_data.total_rainfall IS 'Total monthly precipitation in millimeters';
COMMENT ON COLUMN country_weather_data.avg_soil_moisture IS 'Average soil moisture (0-1 scale) at 0-10cm depth';
COMMENT ON COLUMN country_weather_data.avg_solar_radiation IS 'Average solar radiation in W/m²';
COMMENT ON COLUMN country_weather_data.source IS 'Data source (e.g., open-meteo, manual)';
