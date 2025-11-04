# Claude Code Context: Coffee Database & Knowledge Graph

This document provides context for Claude Code when working on the beans-finder project.

## Project Overview

**beans-finder** is a centralized coffee database and knowledge graph system that aggregates specialty coffee data from UK roasters. It uses AI-powered web crawling to extract and standardize coffee product information.

### Core Technologies
- **Spring Boot 3.2** - Backend framework
- **PostgreSQL** - Primary database with JSONB support
- **Neo4j** - Knowledge graph for flavor relationships
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
- country: VARCHAR
- status: VARCHAR (active, inactive, pending_approval)
- approved: BOOLEAN
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
```
CrawlerScheduler (cron)
  → CrawlerService.crawlAllBrands()
    → WebScraperService.fetchPage() (Jsoup)
    → PerplexityApiService.extractProductData()
    → SCAFlavorWheelService.mapTastingNotes()
    → CoffeeProductRepository.save()
    → KnowledgeGraphService.syncProductToGraph() (Neo4j)
```

## Important Files

### Configuration
- `application.properties` - Main config (use env vars)
- `application-dev.properties` - Local dev (gitignored)
- `.env` - Environment variables (gitignored)
- `docker-compose.yml` - PostgreSQL + Neo4j

### Entities
- `entity/CoffeeBrand.java` - Master entity
- `entity/CoffeeProduct.java` - Detail entity
- `entity/BrandApproval.java` - Approval workflow

### Services
- `service/CrawlerService.java` - Main crawler orchestration
- `service/PerplexityApiService.java` - AI data extraction
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

### Products
- `GET /api/products` - All products
- `GET /api/products/{id}` - Single product
- `GET /api/products/brand/{brandId}` - Products by brand
- `GET /api/products/origin/{origin}` - Products by origin

### Crawler
- `POST /api/crawler/trigger` - Manual crawl all brands
- `POST /api/crawler/retry-failed` - Retry failed products
- `POST /api/crawler/crawl-product?brandId={id}` - Crawl single product URL
  ```json
  { "productUrl": "https://example.com/product" }
  ```

### Knowledge Graph
- `GET /api/graph/flavors` - All flavor nodes
- `GET /api/graph/similar/{productId}` - Similar products by flavor

### Swagger UI
- http://localhost:8080/swagger-ui/index.html

## Common Tasks

### Add New Brand & Crawl Product
```bash
# 1. Add brand via API
curl -X POST http://localhost:8080/api/brands/submit \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Origin Coffee Roasters",
    "website": "https://origincoffee.co.uk",
    "country": "UK",
    "submittedBy": "admin"
  }'

# 2. Approve brand (via admin endpoint or direct DB update)
UPDATE coffee_brands SET approved = true WHERE name = 'Origin Coffee Roasters';

# 3. Crawl a product
curl -X POST 'http://localhost:8080/api/crawler/crawl-product?brandId=1' \
  -H "Content-Type: application/json" \
  -d '{"productUrl": "https://origincoffee.co.uk/products/el-vergel-geisha"}'
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

### Node Types
- **Product** (id, name, brand)
- **Origin** (country, region)
- **Process** (name: Washed, Natural, etc.)
- **Flavor** (name, category)

### Relationship Types
- `(Product)-[:FROM_ORIGIN]->(Origin)`
- `(Product)-[:USES_PROCESS]->(Process)`
- `(Product)-[:HAS_FLAVOR]->(Flavor)`

### Example Cypher Queries
```cypher
// Find products with similar flavors
MATCH (p1:Product)-[:HAS_FLAVOR]->(f:Flavor)<-[:HAS_FLAVOR]-(p2:Product)
WHERE p1.id = 3 AND p1 <> p2
RETURN p2, COUNT(f) as shared_flavors
ORDER BY shared_flavors DESC
LIMIT 10;

// All products from Colombia with Natural process
MATCH (p:Product)-[:FROM_ORIGIN]->(o:Origin {country: 'Colombia'})
MATCH (p)-[:USES_PROCESS]->(pr:Process {name: 'Natural'})
RETURN p;
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

### Test Perplexity Extraction
```bash
# Use /api/crawler/crawl-product to test extraction
curl -X POST 'http://localhost:8080/api/crawler/crawl-product?brandId=1' \
  -H "Content-Type: application/json" \
  -d '{"productUrl": "https://swevencoffee.co.uk/products/el-ocaso-geisha"}' \
  | jq .
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

## Contact & Resources

- **Documentation**: README.md, QUICKSTART.md, INTELLIJ_SETUP.md
- **Swagger**: http://localhost:8080/swagger-ui/index.html
- **Neo4j Browser**: http://localhost:7474
- **Perplexity Docs**: https://docs.perplexity.ai/

---

**Last Updated**: 2025-11-04
**Project Status**: Active Development
**Spring Boot Version**: 3.2.0
**Java Version**: 17+
