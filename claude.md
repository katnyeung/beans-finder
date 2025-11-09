# Claude Code Context: Coffee Database & Knowledge Graph

This document provides context for Claude Code when working on the beans-finder project.

> **Note**: Detailed implementation logs, code examples, and step-by-step instructions have been moved to `IMPLEMENTATION_LOGS.md`

## Project Overview

**beans-finder** is a centralized coffee database and knowledge graph system that aggregates specialty coffee data from UK roasters. It uses AI-powered web crawling to extract and standardize coffee product information.

### Core Technologies
- **Spring Boot 3.3.5** - Backend framework (includes Spring Data Neo4j 7.x)
- **PostgreSQL 16** - Primary database with JSONB support
- **Neo4j 5.14** - Knowledge graph for flavor relationships
- **Perplexity API** - LLM-powered data extraction from HTML
- **OpenAI GPT-4o-mini** - Text extraction (20x cheaper than Perplexity)
- **Playwright** - JavaScript rendering for dynamic sites
- **Jsoup** - HTML parsing
- **Vanilla JavaScript** - Frontend (no frameworks)
- **Swagger/OpenAPI** - API documentation

## Architecture Summary

### Database Schema
- **coffee_brands** - Master table with brand info, sitemap URLs, approval status, geolocation (lat/lon)
- **coffee_products** - Detail table with product data, JSONB fields for tasting notes/SCA flavors
- **brand_approvals** - Approval workflow tracking
- **location_coordinates** - Geocoding cache for brands, origins, producers (reduces API calls)
- **country_weather_data** - Historical weather data cache (monthly aggregates, 2020-2025)

### Key Relationships
- **One-to-Many**: CoffeeBrand → CoffeeProduct
- **Circular Reference Prevention**: @JsonIgnore on both sides (CoffeeBrand.products, CoffeeProduct.brand)
- **JSONB Storage**: Using hypersistence-utils-hibernate-63 for PostgreSQL JSONB

## Critical Implementation Details

### 1. JSON Serialization
**Problem**: Circular references cause `IllegalStateException`
**Solution**: @JsonIgnore on both CoffeeBrand.products and CoffeeProduct.brand

### 2. JSONB Handling
Use `@Type(JsonBinaryType.class)` and `columnDefinition = "jsonb"` for JSONB fields

### 3. SCA Flavor Wheel Mapping
- **Service**: `SCAFlavorWheelService.java`
- **Configuration**: `src/main/resources/config/sca-lexicon.yaml` (YAML-based, hot-editable)
- **Coverage**: 9 categories, 35+ subcategories, 110 attributes, ~250 keyword mappings
- **Based on**: World Coffee Research Sensory Lexicon v2.0 (2017)
- **Visualization**: Grid-based word cloud (vanilla JS, no D3.js)
  - **Zone-based layout**: Each category has its own area (fruity=top, roasted=bottom-left, sweet=right, etc.)
  - **Proximity sorting**: Within each zone, popular items are closer to center
  - **Full-page grid**: No frame boundaries, expands naturally to show all cells
  - **Cell sizing by absolute count** (better distribution):
    - ≥250 products: 4×4 cells (very large categories)
    - 30-249 products: 3×3 cells (popular flavors)
    - 10-29 products: 2×2 cells (common flavors)
    - <10 products: 1×1 cell (rare flavors)
- **Correlation Feature**: Click a flavor to highlight other flavors that co-occur frequently (with percentage)
- **Products Panel**: Click any cell to show products at bottom with clickable links, brand, origin, flavors, price

### 4. Geolocation & Map Visualization
- **Service**: `NominatimGeolocationService.java`
- **Geocoding API**: OpenStreetMap Nominatim (free, rate-limited to 1 req/sec)
- **Cache**: `location_coordinates` table stores geocoded locations to avoid duplicate API calls
- **Strategy**: AI-powered + fallback chain
  - **Brand Addresses**: Perplexity LLM extracts city, full address, and postcode from brand websites
  - **Geocoding Fallback**: address → city → country (most precise location available)
  - **Origin Seeding**: AI batch geocoding from existing product data
  - **Producer Locations**: Extracted during crawling (AI-powered)
- **Geocoding Accuracy Improvements** (V3):
  - **Smart Query Builder**: Uses full address if provided, falls back to city or country
  - **Country Code Filtering**: Adds ISO 3166-1 alpha-2 country codes (e.g., `countrycodes=gb` for UK) to constrain Nominatim search to specific country
  - **Country Mapping**: Supports 50+ country name variations (e.g., "UK", "United Kingdom", "Britain" all map to "gb")
  - **LLM Fallback Geocoding**: When Nominatim fails (specific addresses, unit numbers, new buildings), automatically falls back to OpenAI GPT-4o-mini
    - Cost: ~$0.0001 per address (very cheap)
    - Returns accurate coordinates for addresses Nominatim doesn't know
    - Cached with `source='llm'` to track geocoding method
  - **Result**: All addresses geocode successfully with high accuracy
- **Database Schema**:
  - `coffee_brands`: Added `city`, `address`, `postcode` fields (V3 migration)
  - `location_coordinates`: Geocoding cache with `location_name`, `country`, `region`, `latitude`, `longitude`, `boundingBox`, `source`
- **Frontend**: Leaflet.js (mapping library)
  - Interactive map at `/map.html`
  - **Country Boundaries**: GeoJSON overlays from Natural Earth dataset
  - **Color-Based Visualization**:
    - Each brand assigned a unique color from 30-color vibrant palette
    - **Brand markers**: Square shape with unique color (visually distinct from circular origins)
    - **Origin markers**: Circular shape in green (default)
    - Hover over brand: related origin markers change to brand's color + blink animation
    - Click brand: lock color highlighting (persistent until another brand clicked)
    - No connection lines (cleaner, less cluttered view)
  - **Country Hover Animation**:
    - Hover over country boundary: highlights in gold
    - Origin markers in that country: blink + color change to yellow (#FFD700)
  - **Origin Click Interaction**:
    - Click origin marker: shows popup with all related brands and products
    - Products grouped by brand with brand color coding
    - Each product shown with clickable link, price, roast level
  - **Country Click Weather Charts** (NEW):
    - Click any country boundary: shows popup with historical weather data
    - Year-over-year monthly comparison (2020-2025)
    - 3 metrics with tabbed interface: Temperature (°C), Rainfall (mm), Solar Radiation (W/m²)
    - Chart.js area charts with layered visualization
    - Color-coded by year: Blue (2020), Green (2021), Orange (2022), Red (2023), Purple (2024), Teal (2025)
    - Fading fill effect: Older years have lighter fills, recent years have darker fills
    - Clear colored border lines for easy year identification
    - Areas overlap to show year-over-year trends clearly
    - Smooth curves show seasonal patterns
    - Data source: Open-Meteo API (free, cached on-demand)
  - Toggle layers: brands, origins, producers
  - Visualize supply chains: Brand → Origin Country → Producer Farm (via color matching)
- **Coordinate Sync**: Automatically syncs PostgreSQL coordinates to Neo4j nodes (BrandNode, OriginNode, ProducerNode)
- **Precision Improvement**: Brands are now mapped to specific shop addresses instead of country centers, enabling accurate local coffee shop discovery

### 6. Weather Data Integration
- **Service**: `OpenMeteoWeatherService.java`
- **API**: Open-Meteo Historical Weather Archive (free, no API key required)
- **Database**: `country_weather_data` table stores monthly aggregates (2020-2025)
- **Storage Format**: Country code + year + month → temperature, rainfall, solar radiation
- **Metrics Available**:
  - **Temperature**: Average monthly temperature (°C)
  - **Rainfall**: Total monthly precipitation (mm)
  - **Solar Radiation**: Average daily solar radiation (W/m²)
  - **Note**: Soil moisture NOT available (only in hourly API, not daily aggregates)
- **Data Range**: January 2020 - Current date (updated monthly)
- **Visualization**: Year-over-year monthly comparison
  - X-axis: 12 months (Jan-Dec)
  - Lines: One per year (2020-2025), color-gradient from blue to red
  - Metrics: Switch between temperature/rainfall/solar using tabs
- **Use Case**: Understand climate trends in coffee-growing regions, correlate weather patterns with harvest quality
- **Frontend**: Chart.js for interactive line graphs
- **Cost**: $0 (completely free API)

### 7. Crawler Architecture

#### Hybrid AI Strategy
- **OpenAI GPT-4o-mini**: Text extraction (primary, $0.0004 per product)
- **Playwright**: JavaScript rendering for dynamic sites
- **Perplexity**: Brand discovery only (not used for product extraction due to cost)

#### Sitemap Crawling Flow
1. **Cleanup**: Delete existing products (PostgreSQL + Neo4j)
2. **Sitemap Parsing**: Auto-detect product sitemaps, extract URLs
3. **Keyword Filtering**: Reverse filter excludes 100+ non-coffee keywords:
   - **Equipment brands**: Hario, Kalita, Wilfa, Baratza, Fellow, Acaia, La Marzocco, Sage, etc.
   - **Equipment types**: grinder, kettle, scale, dripper, machine, brewer, filter-paper
   - **Merchandise**: tote, mug, cup, t-shirt, keepcup, huskee
   - **Cleaning**: cafetto, puly, cleaner, descaler
   - **Subscriptions & gifts**: subscription, gift-card, voucher
   - **Other**: tea, capsule, pod, training, course
4. **Playwright + OpenAI Extraction**: Render JS → Extract text → OpenAI extraction
5. **Save & Sync**: Save to PostgreSQL, map SCA flavors, sync to Neo4j, rebuild map cache

#### Cost Analysis (40 products)
- **Current approach**: Playwright + OpenAI = ~$0.016 (cost-effective)
- **Old approach (deprecated)**: Perplexity batch = ~$3.47 (200x more expensive, limited to 15 products)
- **Per product cost**: $0.0004 (OpenAI GPT-4o-mini)

## Important Files

### Configuration
- `application.properties` - Main config
- `docker-compose.yml` - PostgreSQL + Neo4j
- `config/*.java` - JPA, Neo4j, OpenAPI configs
- `src/main/resources/config/sca-lexicon.yaml` - SCA flavor wheel (YAML-editable)

### Backend
- `entity/` - CoffeeBrand, CoffeeProduct, BrandApproval, LocationCoordinates, CountryWeatherData
- `service/` - CrawlerService, PerplexityApiService, OpenAIService, PlaywrightScraperService, SCAFlavorWheelService, KnowledgeGraphService, NominatimGeolocationService, OpenMeteoWeatherService
- `controller/` - BrandController, ProductController, CrawlerController, KnowledgeGraphController, FlavorWheelController, MapController, GeolocationController

### Frontend
- `resources/static/*.html` - index, brands, products, flavor-wheel, map
- `resources/static/js/*.js` - brands, products, flavor-wheel, map (vanilla JS + Leaflet.js for maps)
- `resources/static/css/styles.css` - Coffee-themed styling

## API Endpoints Summary

### Brands
- `GET /api/brands/approved` - Approved brands
- `POST /api/brands/submit` - Submit for approval
- `GET /api/brands/generate-list?country={}&limit={}` - AI-powered brand discovery (includes city, address, postcode extraction)
- `POST /api/brands/bulk-submit` - Bulk submit brands
- `POST /api/brands/auto-setup` - Auto-setup brand by name
- `POST /api/brands/extract-addresses-batch?force={}` - Extract addresses for existing brands using Perplexity AI (NEW)

### Products
- `GET /api/products/brand/{brandId}` - Products by brand
- `GET /api/products/origin/{origin}` - Products by origin

### Crawler
- `POST /api/crawler/crawl-from-sitemap?brandId={}` - Crawl all products from sitemap (RECOMMENDED)
- `POST /api/crawler/crawl-product?brandId={}` - Discover and crawl via Perplexity AI

### Flavor Wheel (WCR Sensory Lexicon v2.0)
- `GET /api/flavor-wheel/data` - Complete hierarchy for visualization
- `GET /api/flavor-wheel/categories` - List all 9 SCA categories
- `GET /api/flavor-wheel/products?category={}&flavor={}` - Find products by flavor
- `GET /api/flavor-wheel/search?flavors={}&matchAll=true` - Multi-flavor search
- `GET /api/flavor-wheel/subcategories/all` - 3-tier WCR hierarchy
- `GET /api/flavor-wheel/correlations?flavor={}` - Get flavors that co-occur frequently (NEW)

### Map & Geolocation
**Map Visualization**:
- `GET /api/map/brands` - All brands with coordinates
- `GET /api/map/origins` - All coffee origins with coordinates
- `GET /api/map/producers` - All producers with coordinates
- `GET /api/map/connections/{brandId}` - Brand → Origins → Producers mapping
- `GET /api/map/data` - Complete dataset for map (brands, origins, producers, connections)

**Geocoding Operations**:
- `POST /api/geolocation/geocode-brand/{brandId}` - Geocode a single brand (uses address → city → country fallback)
- `POST /api/geolocation/geocode-origin?country={}&region={}` - Geocode an origin
- `POST /api/geolocation/batch-geocode-brands?force={}` - Batch geocode all brands (NOTE: Delete old cache entries first if coordinates are wrong)
- `POST /api/geolocation/batch-geocode-origins?force={}` - Batch geocode all origins
- `POST /api/geolocation/seed-origins-from-products` - AI-powered extraction and geocoding of origins from existing products

**Important Note**: If you already have cached coordinates that are incorrect (e.g., from geocoding with just "UK" before addresses were added), you need to:
1. Delete old entries: `DELETE FROM location_coordinates WHERE country='UK' AND location_name='UK';` (or similar)
2. Then re-run: `POST /api/brands/extract-addresses-batch?force=true`
3. Or manually: `UPDATE coffee_brands SET latitude=NULL, longitude=NULL WHERE country='UK';` then batch geocode

### Weather Data
- `GET /api/map/weather/{countryCode}` - Get historical weather data for a country (monthly trends by year)
  - Example: `/api/map/weather/CO` returns Colombia climate data (2020-2025)
  - Response includes: temperatureByYear, rainfallByYear, soilMoistureByYear, solarRadiationByYear
  - Data format: {year: [12 monthly values]} for year-over-year comparison
  - Returns 404 if no data available for country

### Knowledge Graph
**Query**:
- `GET /api/graph/products/brand/{brandName}` - Products by brand
- `GET /api/graph/products/flavor/{flavorName}` - Products by flavor (case-insensitive)
- `GET /api/graph/products/roast-level/{level}` - Products by roast level
- `GET /api/graph/products/origin/{country}` - Products by origin

**Management**:
- `POST /api/graph/cleanup-and-rebuild` - Complete wipe & rebuild
- `POST /api/graph/re-sync-brand/{brandId}` - Re-sync one brand
- `POST /api/graph/fix-core-country-nodes` - Fix missing core country nodes
- `POST /api/graph/fix-malformed-origins` - Fix empty regions/bad IDs
- `POST /api/graph/cleanup-orphans` - Remove orphaned nodes

## Common Tasks

See `IMPLEMENTATION_LOGS.md` for detailed examples.

### Quick Workflow
1. **Generate brands**: `GET /api/brands/generate-list?country=UK&limit=20`
2. **Bulk submit**: `POST /api/brands/bulk-submit`
3. **Approve**: `POST /api/brands/approvals/{id}/approve`
4. **Crawl products**: `POST /api/crawler/crawl-from-sitemap?brandId={id}`

### Development
```bash
# Start databases
docker-compose up -d

# Run with dev profile
# VM Options: -Dspring.profiles.active=dev
```

### Testing
- Swagger UI: http://localhost:8080/swagger-ui/index.html
- Frontend: http://localhost:8080/
  - Brands: http://localhost:8080/brands.html
  - Flavor Wheel: http://localhost:8080/flavor-wheel.html
  - Origins Map: http://localhost:8080/map.html
- Neo4j Browser: http://localhost:7474

## Neo4j Knowledge Graph

### Node Types
- **Product** - productId (Long), relationships to all other nodes
- **Brand** - name (String), brand metadata, latitude, longitude (geolocation)
- **RoastLevel** - level (Light/Medium/Dark/Omni/Unknown)
- **Flavor** - name (lowercase), scaCategory, scaSubcategory
- **Origin** - country, region, latitude, longitude, boundingBox (geolocation)
- **Process** - type (Washed, Natural, etc.)
- **Variety** - name (Geisha, Caturra, etc.)
- **Producer** - name, country, address, city, region, latitude, longitude (geolocation)
- **SCACategory** - name (fruity, floral, etc.)

### Multi-Value Field Handling
System auto-splits multi-value fields (e.g., "Costa Rica / Ethiopia") and creates multiple nodes with relationships.

### Relationship Types
- `SOLD_BY`, `ROASTED_AT`, `HAS_FLAVOR`, `FROM_ORIGIN`, `HAS_PROCESS`, `HAS_VARIETY`, `PRODUCED_BY`, `BELONGS_TO_CATEGORY`

## Known Issues & Solutions

### Circular Reference Error
Use @JsonIgnore on both sides of relationships

### Lazy Loading Exception
Add @Transactional to service methods or use @JsonIgnore

### JSONB Not Saving
Ensure `columnDefinition = "jsonb"` in @Column annotation

### Neo4j Custom Query Mapping
Wrap RETURN clause in a map: `RETURN {key1: value1, key2: value2} as data`

### Perplexity API Debugging
Check logs for: PERPLEXITY REQUEST, RAW RESPONSE, CLEANED RESPONSE, PARSING RESULT

## Environment Variables

**Required**:
```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/coffee_db
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=password
PERPLEXITY_API_KEY=pplx-xxxxx
OPENAI_API_KEY=sk-xxxxx
NEO4J_URI=bolt://localhost:7687
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=password
```

**Optional**:
```bash
CRAWLER_ENABLED=true
CRAWLER_UPDATE_INTERVAL_DAYS=14
CRAWLER_RETRY_ATTEMPTS=3
```

## Project Goals

1. **Centralized Database** - Single source for UK specialty coffee
2. **Automated Updates** - 14-day crawl cycle
3. **AI Extraction** - Handle non-standard websites
4. **Flavor Discovery** - SCA wheel mapping + Neo4j recommendations
5. **Approval Workflow** - Quality control

## Troubleshooting Checklist

1. ✅ Check Docker containers: `docker-compose ps`
2. ✅ Verify environment variables
3. ✅ Check application logs
4. ✅ Test database connection
5. ✅ Verify API keys (Perplexity, OpenAI)
6. ✅ Check @JsonIgnore annotations
7. ✅ Ensure @Transactional on service methods
8. ✅ Test endpoints via Swagger UI

## Resources

- **Detailed Logs**: IMPLEMENTATION_LOGS.md
- **Setup Guide**: INTELLIJ_SETUP.md
- **Swagger**: http://localhost:8080/swagger-ui/index.html
- **Neo4j Browser**: http://localhost:7474
- **WCR Lexicon**: https://worldcoffeeresearch.org/work/sensory-lexicon/

---

**Last Updated**: 2025-11-07
**Project Status**: Active Development
**Branch**: `claude/coffee-database-knowledge-graph-011CUoFQ7AySDKqpeqwL7d18`
