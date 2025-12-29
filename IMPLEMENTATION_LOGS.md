# Implementation Logs

This document contains detailed implementation logs, code examples, and step-by-step instructions that were moved from claude.md for reference purposes.

---

## Detailed API Usage Examples

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

---

## Testing Examples

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

---

## Neo4j Data Management

### Fixing Existing Data

#### Quick Fix - Add Missing Core Country Nodes
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

#### Full Re-sync - Delete and Recreate
```bash
# Re-sync all products (deletes and recreates with new splitting logic)
curl -X POST http://localhost:8080/api/graph/re-sync-all

# Re-sync one brand's products
curl -X POST http://localhost:8080/api/graph/re-sync-brand/43
```

#### Cleanup Orphans
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

#### Fix Malformed Origins
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

#### Recommended Data Quality Workflow
```bash
# Run in this order for best results:
1. curl -X POST http://localhost:8080/api/graph/fix-malformed-origins
2. curl -X POST http://localhost:8080/api/graph/fix-core-country-nodes
3. curl -X POST http://localhost:8080/api/graph/re-sync-all  # Optional if multi-value splitting needed
4. curl -X POST http://localhost:8080/api/graph/cleanup-orphans
```

---

## PostgreSQL JSONB Examples

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

---

## Development Setup Details

### Local Setup Steps
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

### Git Workflow
```bash
git add -A
git commit -m "Your message"
git push -u origin claude/coffee-database-knowledge-graph-011CUoFQ7AySDKqpeqwL7d18
```

---

## Debugging Examples

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

---

## Multi-Value Field Handling Example

**Example Product:**
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

---

## Example Cypher Queries

```cypher
// Find all products by brand
MATCH (p:Product)-[:SOLD_BY]->(b:Brand {name: "Assembly Coffee"})
RETURN p

// Find products with "chocolate" flavor (case-insensitive, works for both SCA + tasting notes)
MATCH (p:Product)-[:HAS_FLAVOR]->(f:Flavor {name: "chocolate"})
RETURN p

// Find products with BOTH "chocolate" AND "caramel" flavors
MATCH (p:Product)-[:HAS_FLAVOR]->(f:Flavor)
WHERE f.name IN ["chocolate", "caramel"]
WITH p, COUNT(DISTINCT f) as matchCount
WHERE matchCount = 2
RETURN p

// Find similar products by shared flavors
MATCH (p1:Product {productId: 421})-[:HAS_FLAVOR]->(f:Flavor)
      <-[:HAS_FLAVOR]-(p2:Product)
WHERE p1 <> p2
RETURN p2.productName, COUNT(f) as sharedFlavors
ORDER BY sharedFlavors DESC
LIMIT 5

// Find brands selling Ethiopian coffee
MATCH (b:Brand)<-[:SOLD_BY]-(p:Product)-[:FROM_ORIGIN]->(o:Origin {country: "Ethiopia"})
RETURN DISTINCT b

// Find light roast products
MATCH (p:Product)-[:ROASTED_AT]->(r:RoastLevel {level: "Light"})
RETURN p

// All products from Colombia with Natural process
MATCH (p:Product)-[:FROM_ORIGIN]->(o:Origin {country: 'Colombia'})
MATCH (p)-[:HAS_PROCESS]->(pr:Process {type: 'Natural'})
RETURN p

// Find all flavors in a category (SCA)
MATCH (f:Flavor)-[:BELONGS_TO_CATEGORY]->(c:SCACategory {name: 'fruity'})
RETURN f.name
```

---

## SCA Lexicon Configuration Example

**Editing the Lexicon:**
```bash
# Edit the YAML file
vim src/main/resources/config/sca-lexicon.yaml

# Add new keywords to existing subcategories:
categories:
  fruity:
    subcategories:
      berry:
        keywords:
          - berry
          - berries
          - your_new_keyword  # Add here!

# Restart application to reload
mvn spring-boot:run
```

---

## Frontend Integration Example

```javascript
const API_BASE = 'http://localhost:8080/api';

// Fetch brands
const response = await fetch(`${API_BASE}/brands/approved`);
const brands = await response.json();

// Parse JSONB fields
const tastingNotes = JSON.parse(product.tastingNotesJson || '[]');
```

---

**Last Updated**: 2025-11-07
