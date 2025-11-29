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
- **Grok (X.AI)** - RAG-powered chatbot with Neo4j knowledge graph
- **Playwright** - JavaScript rendering for dynamic sites
- **Jsoup** - HTML parsing
- **Vanilla JavaScript** - Frontend (no frameworks)
- **Leaflet.js** - Interactive map visualization
- **Chart.js** - Weather data charts
- **Swagger/OpenAPI** - API documentation

## Architecture Summary

### Database Schema
- **coffee_brands** - Master table with brand info, sitemap URLs, approval status (`approved` field), geolocation (lat/lon)
- **coffee_products** - Detail table with product data, JSONB fields for tasting notes/SCA flavors
- **location_coordinates** - Geocoding cache for brands, origins, producers (reduces API calls)
- **country_weather_data** - Historical weather data cache (monthly aggregates, 2020-2025)
- **chatbot_conversations** - Persistent conversation state with JSONB for messages (includes products) and shown_product_ids (V5 migration)

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
- **Products Panel**: Click any cell to show products table at bottom
  - Columns: Product (clickable link), Brand, Origin, Roast, Flavors, Price
  - Product names link to detail page (no Actions column)

### 4. Geolocation & Map Visualization
- **Service**: `NominatimGeolocationService.java`
- **Geocoding API**: OpenStreetMap Nominatim (free, rate-limited to 1.5 sec between requests)
- **User-Agent**: Required by Nominatim - includes contact email for abuse reports
- **Cache**: `location_coordinates` table stores geocoded locations to avoid duplicate API calls
- **LLM Fallback**: Automatically uses OpenAI GPT-4o-mini (~$0.0001/location) when Nominatim fails or blocks
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
    - **Brand markers**: Square shape with unique color (visually distinct from circular origins), z-index: 100
    - **Origin markers**: Circular shape in green (default), **z-index: 1000 (highest priority for easy clicking)**
      - **Dynamic sizing by product count**: 1 product = 6px, 2-3 products = 8px, 4-5 products = 10px, 6+ products = 12px
    - **Producer markers**: Circular shape in brown (5px static), z-index: 500
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
  - **Climate Button Weather Charts**:
    - Climate button positioned below top 3 flavor labels for each country
    - Click "🌡️ Climate" button: shows popup with historical weather data (doesn't block country region clicks)
    - Year-over-year monthly comparison (2020-2025)
    - 3 metrics with tabbed interface: Temperature (°C), Rainfall (mm), Solar Radiation (W/m²)
    - Chart.js area charts with layered visualization
    - Color-coded by year: Blue (2020), Green (2021), Orange (2022), Red (2023), Purple (2024), Teal (2025)
    - Fading fill effect: Older years have lighter fills, recent years have darker fills
    - Clear colored border lines for easy year identification
    - Areas overlap to show year-over-year trends clearly
    - Smooth curves show seasonal patterns
    - Data source: Open-Meteo API (free, cached on-demand)
  - **Toggle Layers**: brands, origins, producers, climate buttons, flavor labels (independent controls)
    - **Default visibility**: Brands ✓, Origins ✓, Producers ✓, Climate ✗, Flavors ✗
    - **Dynamic button labels**: "Hide X" when layer visible (active), "Show X" when hidden (inactive)
    - **Country labels behavior**: Hidden by default (no labels on map load), only appear when Climate or Flavors toggle is enabled
  - **Country Labels**: Centered on country boundaries (no horizontal offset), show country name always when visible
  - **Z-Index Layering** (bottom to top): Overlay pane/country boundaries (400) → Shadow pane (500) → Markers (600) → Country labels with climate buttons (700, top layer for clickability)
  - **Pointer Events**: Country boundaries use `pointer-events: visiblePainted` for hover only, climate buttons have full click priority
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

### 7. RAG-Powered Chatbot (LLM-Driven Architecture)
- **Service**: `ChatbotService.java` + `GrokService.java`
- **LLM**: Grok (X.AI) - Cost similar to GPT-4o-mini (~$0.15/$0.60 per 1M tokens)
- **RAG Strategy**: Neo4j knowledge graph as context source (Grok = Brain, Neo4j = Knowledge Base)
- **Prompt Templates**: `src/main/resources/config/grok-decision-prompt.txt` + `grok-ranking-prompt.txt` (hot-editable)
- **Architecture**: **LLM-Driven (NOT Rule-Based) + Stateless (Client-Side State)**
  1. **User Query** → Receive conversation history from client (localStorage)
  2. **Reference Product** → Provided by client in request
  3. **Build GraphContext** → Neo4j count queries (same origin, same roast, similar flavors, etc.)
  4. **Grok Decision** → Call Grok with full context → Grok decides what graph query to run (returns JSON)
  5. **Execute Query** → Run Neo4j query based on Grok's decision (e.g., MORE_CATEGORY, SAME_ORIGIN)
  6. **Filter Shown** → Remove products already shown (client sends shown product IDs)
  7. **Grok Ranking** → Call Grok again to rank top 15 candidates → Return top 5 with reasons
  8. **Client Saves State** → Client persists conversation, shown products, reference product to localStorage
- **Features**:
  - **Client-Side State Management**: All conversations stored in browser localStorage (no database persistence)
  - **Privacy-First**: Each user has independent conversation history (no shared state)
  - **Full Conversation History**: Messages include product recommendations for complete replay
  - **Comparative Search**: "Show me something more bitter/fruity/cheaper/lighter roast"
  - **SCA Category Mapping**: "more bitter" → MORE_CATEGORY queryType with scaCategory='roasted'
  - **Graph Query Types**: SIMILAR_FLAVORS, SAME_ORIGIN, MORE_CATEGORY, SAME_ORIGIN_MORE_CATEGORY, etc.
  - **Grok-Generated Quick Actions**: Suggested next action buttons (e.g., "More Bitter ☕", "Same Origin 🌍")
  - **Reference Product Context**:
    - **Auto-Reference**: First product from each result automatically set as reference for quick actions
    - User can override by clicking "Find Similar" on specific products
    - Prevents empty results when clicking quick actions after failed searches
  - **Backend Fallback Queries**: When reference product is missing, gracefully falls back to category-based search
    - Example: "More Fruity" without reference → searches all products with scaCategory='fruity'
    - Prevents repeated empty results, provides better UX
  - **Shown Products Tracking**: Client tracks shown product IDs to avoid duplicates
- **Frontend Integration**:
  - **Standalone Chat Page**: `/chat.html` - Full-page AI coffee finder interface
  - "Ask Chatbot" button on product detail page redirects to `/chat.html`
  - Quick action buttons auto-generated by Grok (intent → natural language query)
  - **Compact Table Layout** for product recommendations:
    - Excel-like table with columns: Product (Name + Brand), Price, Origin, Roast, Flavors
    - **~70% space reduction** vs. old card layout (40-50px row height vs. 180-200px card height)
    - Product name links to internal product detail page (`/product-detail.html?id=`)
    - Reason shown inline below product name
    - **Origin display**: Shows "Region, Country" if region available, otherwise just "Country"; deduplicates to avoid "Colombia, Colombia"
    - Multiple origins separated by " / " (e.g., "Ethiopia / Kenya")
    - Responsive design: adjusts for mobile screens
    - **No auto-scroll to bottom** - products stay visible after being displayed
  - LocalStorage persistence for: conversationHistory, shownProductIds, referenceProductId
- **Cost Optimization**: Only top 15 graph-filtered candidates sent to Grok (vs. entire catalog)
- **Endpoints**:
  - `POST /api/chatbot/query` - Main chat endpoint (client sends: query, messages, shownProductIds, referenceProductId)

### 8. Product Detail Page
- **URL Pattern**: `/product-detail.html?id={productId}`
- **Database Schema Update** (V7 migration):
  - Added `roast_level` column to `coffee_products` table
  - Automatically synced from Neo4j during graph updates
- **Layout**:
  - **Hero Section**: Product name, brand breadcrumb, action buttons (View on Seller's Website, Ask Chatbot)
  - **Left Column**: Product information card (brand, price, origin, region, roast level, process, variety, altitude, producer, stock status)
  - **Flavor Profile Card**: Color-coded SCA flavor badges (clickable → flavor-wheel.html)
  - **Tasting Notes Card**: Display tastingNotesJson array as styled badges
  - **Description Card**: Raw description from crawling
  - **Right Column**:
    - **Mini-Map**: Leaflet.js embedded map showing origin location with marker
    - **Related Products**: "More from [Brand]" - shows 6 products from same brand
- **Navigation Updates**:
  - **products.html**: Added Actions column with "🔍 Details" and "🛒 Buy" buttons
  - **map.html**: Origin popup products now link to detail page (seller_url as secondary "Buy" link)
  - **flavor-wheel.html**: Product table links to detail page with small action buttons
- **Endpoints**:
  - `GET /api/products/{id}` - Fetch single product with brand info (returns ProductDetailDTO)
  - `GET /api/products/{id}/related?limit=6` - Get related products from same brand
  - `POST /api/products/{id}/request-update` - Re-crawl product page to update information (NEW)
  - `POST /api/products/sync-roast-levels` - Backfill roast levels from Neo4j to PostgreSQL
- **Request Update Feature**:
  - "🔄 Request Update" button triggers Playwright + OpenAI re-crawl
  - **Updates existing product by ID** (no duplicates created)
  - **Extraction Pipeline**:
    - Playwright `extractProductText()` - removes scripts, styles, nav, footer (clean text only)
    - OpenAI GPT-4o-mini analyzes up to 40KB of clean product text (~10,000 tokens)
    - Much better extraction than raw HTML (captures origin, process, variety, etc.)
  - Updates product data (price, tasting notes, flavors, description, stock status, origin, etc.)
  - Confirmation dialog before triggering (warns user it may take time)
  - Loading state (button shows "⏳ Updating...")
  - Auto-refreshes page after successful update
  - Cost: ~$0.0015 per product update (still very cheap)

### 9. Crawler Architecture

#### Hybrid AI Strategy
- **OpenAI GPT-4o-mini**: Text extraction (primary, ~$0.0015 per product with 40KB clean text)
  - **Improved prompt** with detailed instructions for blends, multiple origins, processes, producers
  - Handles complex products like "Ethiopia / El Salvador" blends
  - Extracts: origin, region, process, producer, variety, altitude, tasting notes, price, stock status
- **Playwright**: JavaScript rendering + clean text extraction (removes scripts/styles/nav)
- **Perplexity**: Brand discovery only (not used for product extraction due to cost)

#### Sitemap Crawling Flow
1. **Cleanup**: Delete existing products (PostgreSQL + Neo4j)
2. **Sitemap Parsing**: Auto-detect product sitemaps, extract URLs with metadata
3. **Two-Stage Filtering** (zero OpenAI cost until page extraction):
   - **Stage 1 - URL Filter**: Exclude collection pages (`/shop/`, `/products/`) and obvious non-coffee paths
   - **Stage 2 - Title Filter**: Parse `<image:title>` from sitemap XML and filter by product name
     - **Inclusion patterns** (coffee-specific): Country names (Ethiopia, Colombia, Kenya), coffee terms (espresso, blend, decaf, roast), processing methods (natural, washed, honey), varieties (geisha, bourbon, typica)
     - **Exclusion patterns** (equipment/merch): Equipment brands (Hario, Acaia, Fellow, La Marzocco), equipment types (grinder, kettle, scale, machine), courses (SCA Brewing, SCA Barista Skills, training, fundamentals, professional, intermediate, foundation), merchandise (mug, tote, t-shirt), tea products
   - **Result**: 125 URLs → ~27 coffee products (78% reduction before any API calls)
4. **Playwright + OpenAI Extraction**: Render JS → Extract text → OpenAI extraction (only for filtered coffee URLs)
5. **Save & Sync**: Save to PostgreSQL, map SCA flavors, sync to Neo4j, rebuild map cache

#### Cost Analysis (40 products)
- **Current approach**: Playwright + OpenAI = ~$0.032 (cost-effective)
- **Old approach (deprecated)**: Perplexity batch = ~$3.47 (100x more expensive, limited to 15 products)
- **Per product cost**: ~$0.0008 (OpenAI GPT-4o-mini with 20KB context for detailed tasting notes)

## Important Files

### Configuration
- `application.properties` - Main config
- `docker-compose.yml` - PostgreSQL + Neo4j
- `config/*.java` - JPA, Neo4j, OpenAPI configs
- `src/main/resources/config/sca-lexicon.yaml` - SCA flavor wheel (YAML-editable)

### Backend
- `entity/` - CoffeeBrand, CoffeeProduct, LocationCoordinates, CountryWeatherData, ConversationContext (@Entity)
- `dto/` - ChatbotRequest, ChatbotResponse, ProductRecommendation, GraphContext, GrokDecision, ExtractedProductData
- `repository/` - ConversationRepository (JPA), ProductNodeRepository (Neo4j with graph count queries)
- `service/` - CrawlerService, PerplexityApiService, OpenAIService, GrokService, ChatbotService (LLM-driven), PlaywrightScraperService, SCAFlavorWheelService, KnowledgeGraphService, NominatimGeolocationService, OpenMeteoWeatherService
- `controller/` - BrandController, ProductController, CrawlerController, ChatbotController, KnowledgeGraphController, FlavorWheelController, MapController, GeolocationController

### Frontend
- `resources/static/*.html` - index, brands, products, flavor-wheel, map, product-detail, **chat** (new full-page chatbot)
- `resources/static/js/*.js` - brands, products, chatbot, flavor-wheel, map, product-detail (vanilla JS + Leaflet.js for maps)
- `resources/static/css/styles.css` - Coffee-themed styling (includes chatbot panel styles, product detail page, modal styles)
- **Standard Navigation**: All pages have consistent header with: Home, **Chat**, Brands, Flavor Wheel, Map
- **Brands Page Features**:
  - Product search by name (searches across all products)
  - "Suggest a Brand" modal with Google reCAPTCHA v2
  - Link to AI Chat for advanced search
- **Products Page**: Product names are clickable links to detail page

## API Endpoints Summary

### Brands
- `GET /api/brands/approved` - Approved brands
- `GET /api/brands/pending` - Pending brands (awaiting approval)
- `POST /api/brands` - Create new brand (pending approval by default)
- `POST /api/brands/{id}/approve` - Approve a brand
- `POST /api/brands/{id}/reject` - Reject a brand
- `GET /api/brands/generate-list?country={}&limit={}` - AI-powered brand discovery (includes city, address, postcode extraction)
- `POST /api/brands/bulk-submit` - Bulk submit brands
- `POST /api/brands/auto-setup` - Auto-setup brand by name
- `POST /api/brands/extract-addresses-batch?force={}` - Extract addresses for existing brands using Perplexity AI
- `POST /api/brands/suggest` - **Public endpoint** for users to suggest a brand (requires reCAPTCHA)

### Products
- `GET /api/products/brand/{brandId}` - Products by brand
- `GET /api/products/origin/{origin}` - Products by origin
- `GET /api/products/search?query={}&limit={}` - **Search products by name** (case-insensitive partial match)

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
- `GET /api/map/origins` - All coffee origins with coordinates (deduplicated by coordinates with 4 decimal precision ~11m, excludes blends/multi-origins)
- `GET /api/map/producers` - All producers with coordinates
- `GET /api/map/connections/{brandId}` - Brand → Origins → Producers mapping
- `GET /api/map/data` - Complete dataset for map (brands, origins, producers, connections, deduplicated origins, filtered blends)

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

### Chatbot (RAG-Powered, Stateless)
- `POST /api/chatbot/query` - Ask chatbot for coffee recommendations
  - Request: `{query, messages[], shownProductIds[], referenceProductId?}`
  - Response: `{products[], explanation, suggestedActions[]}`
  - Client manages: conversation history, shown products, reference product (all in localStorage)
  - Supports: Exact product search, vague keywords, comparative search
  - Examples:
    - "Show me fruity Ethiopian coffee under £15"
    - "Find something similar to this product" (with referenceProductId)
    - "Show me something more bitter" (client tracks reference product)

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
3. **Check pending**: `GET /api/brands/pending`
4. **Approve**: `POST /api/brands/{id}/approve`
5. **Crawl products**: `POST /api/crawler/crawl-from-sitemap?brandId={id}`

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

### Rate Limit Considerations
**Neo4j Aura Free Tier**: 125 requests per minute

**Caching Strategy** (to stay within limits):
1. **Static Cache Files** (Zero Neo4j queries for these endpoints):
   - `/cache/map-data.json` - Map visualization (brands, origins, producers, connections)
   - `/cache/flavors-by-country.json` - Country flavor data for map labels
   - `/cache/flavor-wheel-data.json` - **NEW**: Flavor wheel hierarchy (categories, flavors, product counts)
   - Frontend loads static JSON files directly (no database queries)
   - Cache rebuilt via `POST /api/map/rebuild-cache` (manual trigger, rebuilds all 3 caches)

2. **Neo4j Query Usage** (by endpoint):
   - **Chatbot** (`POST /api/chatbot/query`): ~7-10 queries per request
     - 4 count queries (same origin, same roast, same process, similar flavors)
     - 1 graph query (based on Grok decision)
     - Reference product lookup (if needed)
   - **Flavor Wheel - Click Product** (`GET /api/flavor-wheel/products?flavor=X`): 1 query per flavor click
   - **Knowledge Graph Sync** (`POST /api/graph/cleanup-and-rebuild`): Bulk operations (manual trigger)
   - **Map Cache Rebuild** (`POST /api/map/rebuild-cache`): 4 queries (manual trigger)
     - All brands (via brandNodeRepository.findAll())
     - All origins (via originNodeRepository.findAll())
     - All producers (via producerNodeRepository.findAll())
     - All flavors with counts (via flavorNodeRepository.findAllFlavorsWithProductCountsAsMap())

3. **Typical User Flow** (per minute):
   - Map page load: **0 queries** (uses static cache)
   - **Flavor wheel page load: 0 queries** (uses static cache - **NEW OPTIMIZATION**)
   - Click 5 flavors: **5 queries**
   - Chatbot: 5 questions × 8 queries = **40 queries**
   - **Total: ~45 queries/minute** (well under 125 limit, 36% usage)

4. **Cache Rebuild Frequency**:
   - Manual trigger only (via `/api/map/rebuild-cache`)
   - Recommended: After brand crawling/updates
   - Takes ~3-7 seconds, writes 3 static JSON files

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
GROK_API_KEY=xai-xxxxx
NEO4J_URI=bolt://localhost:7687
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=password
```

**Optional**:
```bash
CRAWLER_ENABLED=true
CRAWLER_UPDATE_INTERVAL_DAYS=14
CRAWLER_RETRY_ATTEMPTS=3

# Google reCAPTCHA (for brand suggestion form)
RECAPTCHA_SITE_KEY=your-site-key
RECAPTCHA_SECRET_KEY=your-secret-key

# Chatbot Rate Limiting & Cost Control
CHATBOT_RATE_LIMIT_PER_MINUTE=5    # Max requests per minute per IP
CHATBOT_RATE_LIMIT_PER_DAY=100     # Max requests per day per IP
CHATBOT_COST_DAILY_LIMIT=1.00      # Daily budget in USD (stops service if exceeded)
```

## Project Goals

1. **Centralized Database** - Single source for UK specialty coffee
2. **Automated Updates** - 14-day crawl cycle
3. **AI Extraction** - Handle non-standard websites
4. **Flavor Discovery** - SCA wheel mapping + Neo4j recommendations
5. **Simple Approval** - Brands have `approved` boolean field for quality control

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
