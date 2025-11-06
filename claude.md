# Claude Code Context: Coffee Database & Knowledge Graph

This document provides context for Claude Code when working on the beans-finder project.

## Project Overview

**beans-finder** is a centralized coffee database and knowledge graph system that aggregates specialty coffee data from UK roasters. It uses AI-powered web crawling to extract and standardize coffee product information.

### Core Technologies
- **Spring Boot 3.3.5** - Backend framework (includes Spring Data Neo4j 7.x)
- **PostgreSQL 16** - Primary database with JSONB support
- **Neo4j 5.14** - Knowledge graph for flavor relationships
- **Perplexity API** - LLM-powered data extraction from HTML
- **Jsoup** - HTML parsing
- **Vanilla JavaScript** - Frontend (no frameworks)
- **Swagger/OpenAPI** - API documentation

## Architecture

### Database Schema

#### coffee_brands (Master Table)
```sql
- id: BIGSERIAL PRIMARY KEY
- name: VARCHAR (unique)
- description: TEXT
- website: VARCHAR
- sitemap_url: VARCHAR (URL to product-sitemap.xml for bulk crawling)
- country: VARCHAR
- status: VARCHAR (active, inactive, pending_approval)
- approved: BOOLEAN
- approved_by: VARCHAR
- approved_date: TIMESTAMP
- crawl_interval_days: INTEGER (default: 14)
- crawl_config: TEXT (JSON)
- products: JSONB (array of product IDs) - denormalized
- user_suggestions: JSONB (array)
- last_crawl_date: TIMESTAMP
- created_date: TIMESTAMP
```

#### coffee_products (Detail Table)
```sql
- id: BIGSERIAL PRIMARY KEY
- brand_id: BIGINT (FK to coffee_brands)
- product_name: VARCHAR
- origin: VARCHAR (country)
- region: VARCHAR (farm/region)
- process: VARCHAR (Washed, Natural, Honey, Anaerobic, etc.)
- producer: VARCHAR
- variety: VARCHAR (Geisha, Caturra, etc.)
- altitude: VARCHAR
- tasting_notes_json: JSONB (array of strings)
- sca_flavors_json: JSONB (SCA flavor wheel mapping)
- seller_url: VARCHAR
- price: NUMERIC
- currency: VARCHAR(3)
- in_stock: BOOLEAN
- crawl_status: VARCHAR (pending, in_progress, done, error)
- raw_description: TEXT
- error_message: TEXT
- last_update_date: TIMESTAMP
```

#### brand_approvals (Workflow Table)
```sql
- id: BIGSERIAL PRIMARY KEY
- brand_id: BIGINT (FK)
- submitted_by: VARCHAR
- status: VARCHAR (pending, approved, rejected)
- reviewed_by: VARCHAR
- review_notes: TEXT
- submission_date: TIMESTAMP
- review_date: TIMESTAMP
```

### Key Relationships
- **One-to-Many**: CoffeeBrand → CoffeeProduct (via @OneToMany and brand_id FK)
- **Denormalized**: CoffeeBrand.products JSONB array contains product IDs for fast queries
- **Important**: Both entity classes use @JsonIgnore on relationship fields to prevent circular reference errors

## Critical Implementation Details

### 1. JSON Serialization (IMPORTANT!)
**Problem**: Circular references between CoffeeBrand ↔ CoffeeProduct cause `IllegalStateException: Cannot call sendError() after the response has been committed`

**Solution**:
```java
// CoffeeBrand.java
@JsonIgnore
@OneToMany(mappedBy = "brand", ...)
private List<CoffeeProduct> products;

// CoffeeProduct.java
@JsonIgnore
@ManyToOne(fetch = FetchType.LAZY)
private CoffeeBrand brand;
```

### 2. JSONB Handling
Uses `hypersistence-utils-hibernate-63` for PostgreSQL JSONB:
```java
@Type(JsonBinaryType.class)
@Column(columnDefinition = "jsonb")
private String tastingNotesJson;
```

Store as JSON strings, parse on read:
```java
List<String> notes = objectMapper.readValue(product.getTastingNotesJson(), List.class);
```

### 3. SCA Flavor Wheel Mapping
Service: `SCAFlavorWheelService.java`

9 Categories:
1. Fruity (citrus, berry, stone fruit, tropical)
2. Floral (jasmine, rose, chamomile)
3. Sweet (honey, caramel, vanilla, chocolate)
4. Nutty (almond, hazelnut, peanut)
5. Spices (cinnamon, clove, pepper)
6. Roasted (tobacco, toast, smoky)
7. Green (vegetal, herbal)
8. Sour (acetic, citric)
9. Other (unclassified notes)

Maps tasting notes → SCA categories with keyword matching (200+ keywords).

### 4. Perplexity API Integration
- **Prompt Template**: `src/main/resources/prompts/product_extraction_prompt.txt`
- **Service**: `PerplexityApiService.java`
- **Purpose**: Extract structured JSON from messy HTML
- **Retry Logic**: 3 attempts with exponential backoff
- **Configuration**:
  - Model: `sonar-pro` (configurable)
  - Temperature: 0.2 (low for consistency)
  - Timeout: 60 seconds

### 5. Crawler Flow

#### Standard Flow (Traditional HTML Sites)
```
CrawlerScheduler (cron)
  → CrawlerService.crawlAllBrands()
    → WebScraperService.fetchPage() (Jsoup)
    → PerplexityApiService.extractProductData()
    → SCAFlavorWheelService.mapTastingNotes()
    → CoffeeProductRepository.save()
    → KnowledgeGraphService.syncProductToGraph() (Neo4j)
```

#### Playwright + OpenAI Fallback Flow (JavaScript-Rendered Sites) ⭐ HYBRID ARCHITECTURE

**Strategy**: Use the right AI for the right job
- **Perplexity**: Web search, URL filtering, batch extraction (what it's designed for)
- **OpenAI GPT-4o-mini**: Text extraction (20x cheaper: $0.0004 vs $0.008 per product)

```
CrawlerService.crawlFromSitemap()
  → PerplexityApiService.extractProductsFromUrls() (batch extraction)
    Cost: ~$0.05 per 15 products
    Use case: Can visit URLs directly, good for simple sites

  → Check fallback conditions:
    • >70% of extracted products are empty/incomplete, OR
    • <50% extraction rate (e.g., got 1 out of 13 products)

    IF YES (Perplexity batch failed):
      → FALLBACK: Playwright + OpenAI (20x cheaper!)
      → Process in chunks (default: 10 products) to prevent DB timeout:

        For each product URL:
          → PlaywrightScraperService.extractProductText(url)
            ├─ Launch headless Chrome browser
            ├─ Navigate to URL and wait for network idle (2s)
            ├─ Execute JavaScript and render page
            ├─ Extract only product-relevant text (remove nav/footer/scripts)
            └─ Return clean product text (~10KB vs 100KB HTML)

          → OpenAIService.extractFromText(text, brand, url)
            ├─ Send clean text to OpenAI GPT-4o-mini
            ├─ Cost: ~$0.0004 per product (vs $0.008 Perplexity)
            ├─ OpenAI extracts ALL fields with semantic understanding:
            │   • Product name, origin, region, process
            │   • Producer, variety, altitude
            │   • Tasting notes (from ANY location in text)
            │   • Price, stock status, description
            └─ Returns complete ExtractedProductData (90% fields)

          → Save chunk to database every 10 products (prevent connection timeout)

    ELSE:
      → Use Perplexity batch results (success)

  → SCAFlavorWheelService.mapTastingNotes()
  → CoffeeProductRepository.save()
  → KnowledgeGraphService.syncProductToGraph()
```

**Cost Comparison (40 products per brand):**

| Approach | Tokens/Product | Cost/Product | Total Cost | Notes |
|----------|----------------|--------------|------------|-------|
| **Perplexity batch** | 15,000 | $0.045 | $1.80 | Try first, often fails on JS sites |
| **Playwright + Perplexity (old)** | 25,000 | $0.075 | **$3.00** | ❌ Too expensive! |
| **Playwright + OpenAI (new)** | 2,500 | $0.0004 | **$0.016** | ✅ 99.5% cheaper! |

**Real Example: RAVE Coffee (40 products)**
- Old: $3.00 (burned through credits in minutes)
- New: $0.016 (sustainable long-term)

**Key Improvements:**
- **99.5% cost reduction** for fallback extraction ($3.00 → $0.016)
- **90% token reduction** by extracting text instead of full HTML (10KB vs 100KB)
- **Same quality**: OpenAI GPT-4o-mini has excellent extraction accuracy
- **Smart hybrid**: Use Perplexity for search, OpenAI for extraction

**Why This Works:**
- Playwright handles JavaScript execution (Shopify, React, Next.js)
- Perplexity AI intelligently extracts from ANY HTML structure
- No brittle regex patterns to maintain
- Works for all site structures (Shopify metafields, JSON-LD, text content)

**Supported Site Types:**
- Shopify stores (Assembly Coffee, Pact Coffee)
- React/Next.js sites (modern coffee roasters)
- WooCommerce sites
- Custom JavaScript-rendered sites

## Important Files

### Configuration
- `application.properties` - Main config (use env vars)
- `application-dev.properties` - Local dev (gitignored)
- `.env` - Environment variables (gitignored)
- `docker-compose.yml` - PostgreSQL + Neo4j
- `config/JpaConfig.java` - JPA/PostgreSQL transaction manager (primary)
- `config/Neo4jConfig.java` - Neo4j transaction manager (separate to avoid conflicts)
- `config/OpenApiConfig.java` - Swagger/OpenAPI setup

### Entities
- `entity/CoffeeBrand.java` - Master entity
- `entity/CoffeeProduct.java` - Detail entity
- `entity/BrandApproval.java` - Approval workflow

### Services
- `service/CrawlerService.java` - Main crawler orchestration
- `service/PerplexityApiService.java` - AI web search & URL filtering (batch URLs, brand discovery)
- `service/OpenAIService.java` - AI text extraction (GPT-4o-mini, 20x cheaper than Perplexity)
- `service/PlaywrightScraperService.java` - JavaScript rendering & text extraction
- `service/SCAFlavorWheelService.java` - Flavor categorization
- `service/KnowledgeGraphService.java` - Neo4j sync
- `service/WebScraperService.java` - HTML fetching/parsing
- `service/BrandApprovalService.java` - Approval workflow

### Controllers
- `controller/BrandController.java` - `/api/brands/**`
- `controller/ProductController.java` - `/api/products/**`
- `controller/CrawlerController.java` - `/api/crawler/**`
- `controller/KnowledgeGraphController.java` - `/api/graph/**`

### Frontend
- `resources/static/index.html` - Home page
- `resources/static/brands.html` - Brand listing
- `resources/static/products.html` - Product details
- `resources/static/js/brands.js` - Brand loading logic
- `resources/static/js/products.js` - Product display logic
- `resources/static/css/styles.css` - Coffee-themed styling

### Prompts
- `resources/prompts/product_extraction_prompt.txt` - Perplexity extraction template

## API Endpoints

### Brands
- `GET /api/brands` - All brands
- `GET /api/brands/approved` - Approved brands only
- `GET /api/brands/{id}` - Single brand
- `POST /api/brands/submit` - Submit brand for approval
- `GET /api/brands/generate-list?country={country}&limit={limit}` - Generate brands list via Perplexity AI
- `POST /api/brands/bulk-submit` - Bulk submit multiple brands at once
- `POST /api/brands/auto-setup` - Auto-setup brand by name via Perplexity AI

### Products
- `GET /api/products` - All products
- `GET /api/products/{id}` - Single product
- `GET /api/products/brand/{brandId}` - Products by brand
- `GET /api/products/origin/{origin}` - Products by origin

### Crawler
- `POST /api/crawler/trigger` - Manual crawl all brands
- `POST /api/crawler/retry-failed` - Retry failed products
- `POST /api/crawler/crawl-from-sitemap?brandId={id}` - Crawl all products from brand's sitemap
- `POST /api/crawler/crawl-product?brandId={id}` - **Discover and crawl ALL brand products via Perplexity AI**

### Knowledge Graph
- `GET /api/graph/products/flavor/{flavorName}` - Find products by flavor
- `GET /api/graph/products/sca-category/{categoryName}` - Find products by SCA category
- `GET /api/graph/products/origin/{country}` - Find products by origin
- `GET /api/graph/products/process/{processType}` - Find products by process
- `GET /api/graph/products/complex?process={type}&flavor={name}` - Complex query by process AND flavor
- `POST /api/graph/init-categories` - Initialize SCA categories
- `POST /api/graph/re-sync-all` - **Re-sync ALL products to Neo4j with multi-value splitting**
- `POST /api/graph/re-sync-brand/{brandId}` - **Re-sync one brand's products to Neo4j**
- `POST /api/graph/fix-core-country-nodes` - **One-time fix: Create missing core country nodes**
- `POST /api/graph/fix-malformed-origins` - **Fix empty regions and malformed IDs**
- `POST /api/graph/cleanup-orphans` - **Clean up orphaned Origin/Process/Producer/Variety nodes**
- `GET /api/graph/stats` - Get Neo4j statistics (product count)

### Swagger UI
- http://localhost:8080/swagger-ui/index.html

## Common Tasks

### Auto-Discover and Setup Brands Using Perplexity AI

#### Generate List of Brands with Full Details
```bash
# Get list of 20 UK specialty coffee roasters with full details (website, sitemap, etc.)
# By default, excludes brands already in your database (no duplicates!)
# AUTOMATIC PRODUCT SITEMAP RESOLUTION: If Perplexity returns main sitemap.xml,
# the system automatically extracts the product-specific sitemap URL (e.g., sitemap_products_1.xml)
# Note: This calls Perplexity for each brand, so it takes ~20 seconds for 20 brands
curl 'http://localhost:8080/api/brands/generate-list?country=UK&limit=20&includeDetails=true&excludeExisting=true'

# Response:
# {
#   "brands": [
#     {
#       "name": "Union Roasted",
#       "website": "https://unionroasted.com",
#       "sitemapUrl": "https://unionroasted.com/sitemap_products_1.xml?from=2151265271926&to=15594637623669",
#       "country": "UK",
#       "description": "Specialty coffee roaster..."
#     },
#     {
#       "name": "Origin Coffee",
#       "website": "https://origincoffee.co.uk",
#       "sitemapUrl": "https://origincoffee.co.uk/product-sitemap.xml",
#       "country": "UK",
#       "description": "Award-winning coffee roaster from Cornwall"
#     },
#     ...
#   ],
#   "totalRequested": 20,
#   "totalWithDetails": 18,
#   "totalExcluded": 5,  // Number of brands already in database
#   "message": "Success: 18/20 brands with full details (excluded 5 existing)"
# }

# How Auto-Resolution Works:
# 1. Perplexity discovers sitemap URL (may be main sitemap.xml or product sitemap)
# 2. System fetches the sitemap to check if it's a sitemap index
# 3. If index found, extracts product sitemap URLs (sitemap_products_1.xml, etc.)
# 4. Returns the product-specific sitemap URL with query params preserved

# Fast mode - just get names (no website/sitemap lookup)
curl 'http://localhost:8080/api/brands/generate-list?country=UK&limit=20&includeDetails=false'

# Include all brands (even if they exist in database)
curl 'http://localhost:8080/api/brands/generate-list?country=UK&limit=20&excludeExisting=false'
```

#### Bulk Submit Brands (Copy/Paste from Generate-List)
```bash
# Step 1: Generate list and review the JSON
curl 'http://localhost:8080/api/brands/generate-list?country=UK&limit=10' > brands.json

# Step 2: Review brands.json, edit if needed, then bulk submit
curl -X POST http://localhost:8080/api/brands/bulk-submit \
  -H "Content-Type: application/json" \
  -d '{
    "submittedBy": "admin",
    "brands": [
      {
        "name": "Origin Coffee",
        "website": "https://www.origincoffee.co.uk",
        "sitemapUrl": "https://www.origincoffee.co.uk/sitemap.xml",
        "country": "UK",
        "description": "Award-winning coffee roaster from Cornwall"
      },
      {
        "name": "RAVE Coffee",
        "website": "https://ravecoffee.co.uk",
        "sitemapUrl": "https://ravecoffee.co.uk/sitemap.xml",
        "country": "UK",
        "description": "UK specialty coffee roaster..."
      }
    ]
  }'

# Response:
# {
#   "results": [
#     {
#       "brandName": "Origin Coffee",
#       "status": "success",
#       "approvalId": 1,
#       "message": "Submitted for approval"
#     },
#     {
#       "brandName": "RAVE Coffee",
#       "status": "success",
#       "approvalId": 2,
#       "message": "Submitted for approval"
#     }
#   ],
#   "totalProcessed": 2,
#   "successCount": 2,
#   "skippedCount": 0,
#   "errorCount": 0,
#   "message": "Processed 2 brands: 2 submitted, 0 skipped, 0 errors"
# }
```

#### Auto-Setup Brand by Name (Single Brand)
```bash
# Perplexity will discover: website, sitemap URL, country, description
curl -X POST http://localhost:8080/api/brands/auto-setup \
  -H "Content-Type: application/json" \
  -d '{
    "brandName": "Sweven Coffee",
    "submittedBy": "admin"
  }'

# Response includes discovered details:
# {
#   "approvalId": 1,
#   "name": "Sweven Coffee",
#   "website": "https://www.swevencoffee.co.uk",
#   "sitemapUrl": "https://www.swevencoffee.co.uk/product-sitemap.xml",
#   "country": "UK",
#   "description": "Specialty coffee roaster...",
#   "message": "Brand auto-setup successful. Pending approval."
# }
```

#### Complete Workflow: Generate → Bulk Submit → Approve → Discover Products
```bash
# 1. Generate list of brands
curl 'http://localhost:8080/api/brands/generate-list?country=UK&limit=10'

# 2. Bulk submit (copy JSON from step 1)
curl -X POST http://localhost:8080/api/brands/bulk-submit \
  -H "Content-Type: application/json" \
  -d '{"submittedBy": "admin", "brands": [...]}'

# 3. Approve brands
curl -X POST http://localhost:8080/api/brands/approvals/1/approve \
  -H "Content-Type: application/json" \
  -d '{"reviewedBy": "admin", "reviewNotes": "Approved"}'

# 4. Crawl products from sitemap (RECOMMENDED - uses Perplexity for extraction)
curl -X POST 'http://localhost:8080/api/crawler/crawl-from-sitemap?brandId=1'

# How Sitemap Crawling Works (with automatic product sitemap detection + JS detection):
# 0. **CLEANUP**: Delete ALL existing products for this brand from:
#    - Neo4j knowledge graph (ProductNode + relationships, shared nodes remain)
#    - PostgreSQL (coffee_products table)
#    This ensures fresh data and removes discontinued products
# 1. Fetch the brand's sitemapUrl (could be main sitemap.xml or product sitemap)
# 2. Check if it's a sitemap index:
#    - If YES: Extract all product sitemap URLs (e.g., sitemap_products_1.xml, sitemap_products_2.xml)
#    - If NO: Treat it as a direct product sitemap
# 3. For EACH product sitemap:
#    - Fetch and parse the XML
#    - Extract all <url><loc> entries
#    - **Stage 1: Keyword-based filtering** (fast, local):
#      * Must be in product path: /products/, /product/, /coffees/, /coffee/, /shop/
#      * Exclude obvious non-coffee items:
#        - Path-based: bundles, accessories, equipment, grinders, filters, filter-papers, gift-cards, subscriptions
#        - Keyword-based: sibarist, origami, hario, kalita, chemex, aeropress, v60, dripper, kettle, scale, tamper, mug, cup, tote, book, cleaning, etc.
#    - **Stage 2: AI-based filtering** (smart, Perplexity):
#      * Send ALL filtered URLs to Perplexity for classification
#      * Perplexity analyzes URL paths and patterns
#      * Returns only coffee bean/ground coffee product URLs
#      * Removes equipment, accessories, merchandise that passed stage 1
#      * Example: 129 URLs → 95 coffee bean URLs (removed 34 non-coffee)
# 4. **SMART EXTRACTION** - Try Perplexity first, fallback to Playwright:
#    - ALWAYS try Perplexity batch extraction first (fast, cheap)
#    - IF Perplexity returns >70% empty results (mostly N/A):
#      → Automatic Playwright fallback for ALL URLs
#      → Extract from JSON-LD, Shopify metafields, meta tags
#      → Handles JavaScript-rendered sites (Shopify, Next.js, React)
#    - ELSE: Use Perplexity results (success!)
# 5. Extracted data includes:
#    - Product name, origin, region, process, producer, variety, altitude
#    - ALL tasting notes (taste, sweetness, acidity, mouthfeel, body)
#    - Price, stock status
#    - Product URL (matched to source URL from sitemap)
#    - Raw description text (full product description from page)
# 6. Save products to PostgreSQL with all extracted data including seller_url and raw_description
# 7. Map tasting notes to SCA flavor wheel categories
# 8. Sync to Neo4j knowledge graph for flavor-based recommendations

# Supported Sitemap Formats:
# - Shopify: sitemap_products_1.xml?from=X&to=Y (query params preserved)
# - WooCommerce: product-sitemap.xml
# - Generic: sitemap.xml (auto-detects product sitemap from index)

# Note: /api/crawler/crawl-product is experimental and may not work for all sites
# due to Perplexity API limitations accessing live websites
```

### Manual Brand Setup (Traditional Method)
```bash
# 1. Add brand via API with sitemap URL
curl -X POST http://localhost:8080/api/brands/submit \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Sweven Coffee",
    "website": "https://www.swevencoffee.co.uk",
    "sitemapUrl": "https://www.swevencoffee.co.uk/product-sitemap.xml",
    "country": "UK",
    "description": "Specialty coffee roaster",
    "submittedBy": "admin",
    "submissionNotes": "UK specialty roaster"
  }'

# 2. Approve brand (via admin endpoint or direct DB update)
UPDATE coffee_brands SET approved = true WHERE name = 'Sweven Coffee';

# 3. Crawl all products from sitemap
curl -X POST 'http://localhost:8080/api/crawler/crawl-from-sitemap?brandId=1'

# OR Crawl a single product
curl -X POST 'http://localhost:8080/api/crawler/crawl-product?brandId=1' \
  -H "Content-Type: application/json" \
  -d '{"productUrl": "https://www.swevencoffee.co.uk/products/el-vergel-geisha"}'
```

### Update Perplexity Prompt
1. Edit `src/main/resources/prompts/product_extraction_prompt.txt`
2. Restart Spring Boot (prompt loads on startup)
3. Test with `/api/crawler/crawl-product` endpoint

### Query JSONB in PostgreSQL
```sql
-- Get all products with "Geisha" in tasting notes
SELECT * FROM coffee_products
WHERE tasting_notes_json::jsonb @> '["Geisha"]'::jsonb;

-- Get brands with specific product ID in products array
SELECT * FROM coffee_brands
WHERE products::jsonb @> '[3]'::jsonb;

-- Parse JSONB in query
SELECT
  product_name,
  tasting_notes_json::jsonb->0 AS first_note
FROM coffee_products;
```

## Development Workflow

### Local Setup
```bash
# 1. Start databases
docker-compose up -d

# 2. Copy example config
cp application-dev.properties.example application-dev.properties

# 3. Edit application-dev.properties with real values
# - Set Perplexity API key
# - Set database credentials
# - Disable crawler if testing: crawler.enabled=false

# 4. Run with 'dev' profile in IntelliJ
# VM Options: -Dspring.profiles.active=dev
```

### IntelliJ Configuration
See `INTELLIJ_SETUP.md` for detailed instructions.

**Quick method**: Use `application-dev.properties` + `dev` profile (no plugins needed).

### Testing Endpoints
1. Use Swagger UI: http://localhost:8080/swagger-ui/index.html
2. Or curl/Postman
3. Frontend: http://localhost:8080/brands.html

## Known Issues & Solutions

### Issue: "Cannot call sendError() after response committed"
**Cause**: Circular reference in entity serialization
**Solution**: Ensure @JsonIgnore on both sides of relationships
- CoffeeBrand.products
- CoffeeProduct.brand

### Issue: Lazy loading exception
**Cause**: Accessing lazy-loaded entity outside transaction
**Solution**:
1. Add @Transactional to service methods
2. Use EAGER fetch (not recommended)
3. Use @JsonIgnore and fetch separately

### Issue: JSONB not saving
**Cause**: Missing `columnDefinition = "jsonb"`
**Solution**:
```java
@Type(JsonBinaryType.class)
@Column(columnDefinition = "jsonb")
private String field;
```

### Issue: Perplexity API timeout
**Cause**: Complex pages or API slowness
**Solution**: Increase timeout in PerplexityApiService constructor (default: 60s)

### Issue: Hibernate dialect errors with SQLite
**Cause**: Hibernate 6.x incompatibility
**Solution**: Project uses PostgreSQL now (no custom dialects needed)

## Environment Variables

Required:
```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/coffee_db
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=password
PERPLEXITY_API_KEY=pplx-xxxxx

NEO4J_URI=bolt://localhost:7687
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=password
```

Optional:
```bash
CRAWLER_ENABLED=true
CRAWLER_UPDATE_INTERVAL_DAYS=14
CRAWLER_RETRY_ATTEMPTS=3
```

## Neo4j Knowledge Graph

### Node Types (All use application-generated IDs)
- **Product** (@Id: productId from PostgreSQL - Long)
  - Fields: brand, productName, price, currency, inStock
  - Relationships: Sets of origins, processes, producers, varieties, flavors
- **Origin** (@Id: generated from country-region - String)
  - Fields: country, region, altitude
  - **IMPORTANT**: Core country nodes (region=null) are ALWAYS created for queries
- **Process** (@Id: type - String)
  - Natural key: process type (e.g., "Washed", "Natural")
- **Variety** (@Id: name - String)
  - Natural key: variety name (e.g., "Geisha", "Caturra")
- **Flavor** (@Id: name - String)
  - Fields: scaCategory, scaSubcategory
  - Natural key: flavor name (e.g., "Nashi pear")
- **Producer** (@Id: generated from name-country - String)
  - Fields: name, country
- **SCACategory** (@Id: name - String)
  - Natural key: category name (e.g., "fruity", "floral")

**Note**: All nodes use natural keys or application-generated IDs instead of Neo4j's internal `@GeneratedValue` to avoid deprecation warnings.

### Multi-Value Field Handling ⭐ NEW

The system intelligently handles multi-value fields (e.g., "Costa Rica / Ethiopia") by:

1. **Splitting on `/` and `,` separators** - Automatically detects and splits multi-value fields
2. **Creating multiple nodes** - One node per value (e.g., "Costa Rica / Ethiopia" → 2 OriginNodes)
3. **Linking products to all nodes** - Product links to ALL relevant nodes via multiple relationships
4. **Creating core country nodes** - ALWAYS creates origin node with region=null for country-level queries

**Example:**
```
Product: "Classic Espresso Pack"
Origin: "Costa Rica / Ethiopia"
Region: "Yirgacheffe"
Process: "White Honey / Washed"
Variety: "Geisha, Caturra"

Creates nodes:
- OriginNode {id: "CostaRica", country: "Costa Rica", region: null}
- OriginNode {id: "Ethiopia", country: "Ethiopia", region: null}
- OriginNode {id: "Ethiopia-Yirgacheffe", country: "Ethiopia", region: "Yirgacheffe"}
- ProcessNode {type: "White Honey"}
- ProcessNode {type: "Washed"}
- VarietyNode {name: "Geisha"}
- VarietyNode {name: "Caturra"}

Product links to ALL 7 nodes!
```

**Why Core Country Nodes?**
- Ensures queries like "find all Ethiopia coffees" work correctly
- Product links to BOTH core country node AND specific region node
- Enables flexible querying at country or region level

**Fixing Existing Data:**

There are two approaches to fix existing Neo4j data:

1. **Quick Fix** - Add missing core country nodes without touching existing data:
```bash
# One-time fix: Creates missing core country nodes (e.g., "Ethiopia")
# for existing region nodes (e.g., "Ethiopia-Yirgacheffe")
# Safe to run multiple times (idempotent)
curl -X POST http://localhost:8080/api/graph/fix-core-country-nodes

# Response example:
# {
#   "coreNodesCreated": 15,
#   "relationshipsAdded": 243,
#   "countriesProcessed": 15
# }
```

2. **Full Re-sync** - Delete and recreate all product nodes with multi-value splitting:
```bash
# Re-sync all products (deletes and recreates with new splitting logic)
curl -X POST http://localhost:8080/api/graph/re-sync-all

# Re-sync one brand's products
curl -X POST http://localhost:8080/api/graph/re-sync-brand/43
```

**Which to use?**
- Use **fix-core-country-nodes** if you just want to add missing core nodes quickly
- Use **re-sync-all** if you have multi-value fields that need splitting (e.g., "Costa Rica / Ethiopia")

3. **Cleanup Orphans** - Remove nodes with no product relationships:
```bash
# Clean up orphaned Origin, Process, Producer, Variety nodes
# (Flavor and SCACategory nodes are intentionally preserved)
curl -X POST http://localhost:8080/api/graph/cleanup-orphans

# Response example:
# {
#   "originsDeleted": 23,
#   "processesDeleted": 5,
#   "producersDeleted": 12,
#   "varietiesDeleted": 8,
#   "totalDeleted": 48
# }
```

4. **Fix Malformed Origins** - Clean up nodes with empty regions:
```bash
# Fix malformed OriginNodes:
# - {id: "Brazil-", region: ""} -> {id: "Brazil", region: null}
# - {id: "Colombia-Various", region: "Various"} -> normalized
# - Merges duplicates automatically
curl -X POST http://localhost:8080/api/graph/fix-malformed-origins

# Response example:
# {
#   "nodesFixed": 12,
#   "nodesMerged": 5,
#   "nodesDeleted": 5
# }
```

**Recommended Workflow for Data Quality:**
1. `fix-malformed-origins` - Fix empty regions and bad IDs first
2. `fix-core-country-nodes` - Add missing core country nodes
3. `re-sync-all` - Handle multi-value splitting (optional, if needed)
4. `cleanup-orphans` - Remove any unused nodes

### Relationship Types
- `(Product)-[:FROM_ORIGIN]->(Origin)` - **Multiple** origins per product
- `(Product)-[:HAS_PROCESS]->(Process)` - **Multiple** processes per product
- `(Product)-[:HAS_VARIETY]->(Variety)` - **Multiple** varieties per product
- `(Product)-[:HAS_FLAVOR]->(Flavor)` - **Multiple** flavors per product
- `(Product)-[:PRODUCED_BY]->(Producer)` - **Multiple** producers per product
- `(Flavor)-[:IN_CATEGORY]->(SCACategory)` - Flavor categorization

### Example Cypher Queries
```cypher
// Find products with similar flavors
MATCH (p1:Product)-[:HAS_FLAVOR]->(f:Flavor)<-[:HAS_FLAVOR]-(p2:Product)
WHERE p1.productId = 3 AND p1 <> p2
RETURN p2, COUNT(f) as shared_flavors
ORDER BY shared_flavors DESC
LIMIT 10;

// All products from Colombia with Natural process
MATCH (p:Product)-[:FROM_ORIGIN]->(o:Origin {country: 'Colombia'})
MATCH (p)-[:HAS_PROCESS]->(pr:Process {type: 'Natural'})
RETURN p;

// Find all flavors in a category
MATCH (f:Flavor)-[:BELONGS_TO_CATEGORY]->(c:SCACategory {name: 'fruity'})
RETURN f.name;
```

## Frontend Architecture

### Vanilla JavaScript (No Frameworks)
- **brands.js**: Fetches `/api/brands/approved`, renders grid
- **products.js**: Fetches `/api/products/brand/{id}`, displays products
- **Navigation**: Query params (e.g., `products.html?brandId=1`)

### Styling
- Coffee-themed colors (browns, creams)
- Responsive grid layout
- Mobile-friendly

### API Integration
```javascript
const API_BASE = 'http://localhost:8080/api';

// Fetch brands
const response = await fetch(`${API_BASE}/brands/approved`);
const brands = await response.json();

// Parse JSONB fields
const tastingNotes = JSON.parse(product.tastingNotesJson || '[]');
```

## Scheduled Tasks

### CrawlerScheduler
- **Cron**: `0 0 2 * * ?` (2 AM daily)
- **Action**: Crawls brands with `lastCrawlDate` > 14 days old
- **Config**: `crawler.enabled=true` to enable

## Testing Tips

### Test Product Extraction

#### Test Traditional Sites (Perplexity)
```bash
# Use /api/crawler/crawl-product to test extraction
curl -X POST 'http://localhost:8080/api/crawler/crawl-product?brandId=1' \
  -H "Content-Type: application/json" \
  -d '{"productUrl": "https://swevencoffee.co.uk/products/el-ocaso-geisha"}' \
  | jq .
```

#### Test JavaScript-Rendered Sites (Playwright)
```bash
# Test Assembly Coffee (Shopify with JavaScript rendering)
curl -X POST 'http://localhost:8080/api/crawler/crawl-product?brandId=1' \
  -H "Content-Type: application/json" \
  -d '{"productUrl": "https://assemblycoffee.co.uk/products/ethiopia-rumudamo-natural-2025"}' \
  | jq .

# The system will:
# 1. Detect JavaScript rendering (const metafields, var Shopify)
# 2. Launch Playwright headless browser
# 3. Execute JavaScript and extract from:
#    - Shopify metafields
#    - JSON-LD structured data
#    - Meta tags
# 4. Return structured product data
```

**Check Logs for Playwright:**
```bash
# Look for these log messages:
# - "Detected JavaScript-rendered site, using Playwright"
# - "Successfully extracted with Playwright: [product name]"
# - "Playwright fallback successful: [product name]"
```
```

### Test SCA Mapping
```java
// In a test or via direct service call
List<String> notes = List.of("Nashi pear", "oolong", "cherimoya");
SCAFlavorMapping mapping = scaService.mapTastingNotes(notes);
// Expected: fruity, floral, other
```

### Database Queries
```sql
-- Check crawl status
SELECT brand_id, COUNT(*), crawl_status
FROM coffee_products
GROUP BY brand_id, crawl_status;

-- Recent products
SELECT product_name, origin, process, last_update_date
FROM coffee_products
ORDER BY last_update_date DESC
LIMIT 10;
```

## Git Workflow

**Branch**: `claude/coffee-database-knowledge-graph-011CUoFQ7AySDKqpeqwL7d18`

### Commit & Push
```bash
git add -A
git commit -m "Your message"
git push -u origin claude/coffee-database-knowledge-graph-011CUoFQ7AySDKqpeqwL7d18
```

## Project Goals

1. **Centralized Database**: Single source of truth for UK specialty coffee
2. **Automated Updates**: 14-day crawl cycle keeps data fresh
3. **AI Extraction**: Handle non-standard websites via Perplexity
4. **Flavor Discovery**: SCA wheel mapping + Neo4j for recommendations
5. **Approval Workflow**: Quality control for new brands

## Future Enhancements

- [ ] User authentication & saved preferences
- [ ] Advanced filtering (price, process, origin)
- [ ] Email notifications for new products
- [ ] Mobile app
- [ ] More comprehensive flavor analysis
- [ ] Integration with more roasters
- [ ] Product availability tracking
- [ ] Price history & trends

## Troubleshooting Checklist

When debugging issues:

1. ✅ Check Docker containers are running (`docker-compose ps`)
2. ✅ Verify environment variables are set
3. ✅ Check application logs for errors
4. ✅ Test database connection (`psql` or pgAdmin)
5. ✅ Verify Perplexity API key is valid
6. ✅ Check entity @JsonIgnore annotations
7. ✅ Ensure @Transactional on service methods
8. ✅ Review JSONB column definitions
9. ✅ Test endpoints via Swagger UI first
10. ✅ Check for lazy loading exceptions

### Debugging Perplexity API Calls

When product discovery returns 0 products, check the logs for detailed information:

```bash
# Look for these log sections in your application logs:
=== PERPLEXITY REQUEST ===
Brand: Pact Coffee
Website: https://www.pactcoffee.com
Prompt: [Full prompt sent to Perplexity]

=== PERPLEXITY RAW RESPONSE ===
Response: [Actual response from Perplexity]

=== CLEANED RESPONSE ===
Cleaned: [JSON after cleaning markdown blocks]

=== PARSING RESULT ===
Discovered X products for brand: Pact Coffee
```

**Common Issues:**
- **Empty array `[]`**: Perplexity couldn't find products (check website URL, may need specific product page)
- **Parsing error**: Response wasn't valid JSON (check raw response)
- **Max tokens exceeded**: Increase `max_tokens` in PerplexityApiService.java (currently 2000)

## Contact & Resources

- **Documentation**: README.md, QUICKSTART.md, INTELLIJ_SETUP.md
- **Swagger**: http://localhost:8080/swagger-ui/index.html
- **Neo4j Browser**: http://localhost:7474
- **Perplexity Docs**: https://docs.perplexity.ai/

---

**Last Updated**: 2025-11-05
**Project Status**: Active Development
**Spring Boot Version**: 3.3.5 (includes Spring Data Neo4j 7.x)
**Neo4j Version**: 5.14
**Java Version**: 17+
