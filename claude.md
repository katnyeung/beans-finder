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
- **coffee_brands** - Master table with brand info, sitemap URLs, approval status
- **coffee_products** - Detail table with product data, JSONB fields for tasting notes/SCA flavors
- **brand_approvals** - Approval workflow tracking

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

### 4. Crawler Architecture

#### Hybrid AI Strategy
- **Perplexity**: Web search, URL filtering, batch extraction (try first)
- **OpenAI GPT-4o-mini**: Text extraction fallback (20x cheaper: $0.0004 vs $0.008 per product)
- **Playwright**: JavaScript rendering for dynamic sites

#### Sitemap Crawling Flow
1. **Cleanup**: Delete existing products (PostgreSQL + Neo4j)
2. **Sitemap Parsing**: Auto-detect product sitemaps, extract URLs
3. **Stage 1 Filtering**: Keyword-based (fast, local) - filter product paths, exclude equipment
4. **Stage 2 Filtering**: AI-based (Perplexity) - classify coffee bean URLs only
5. **Smart Extraction**: Try Perplexity batch first, fallback to Playwright + OpenAI if >70% empty
6. **Save & Sync**: Save to PostgreSQL, map SCA flavors, sync to Neo4j

#### Cost Comparison (40 products)
- Perplexity batch: $1.80 (try first, may fail on JS sites)
- Playwright + Perplexity: $3.00 (too expensive)
- Playwright + OpenAI: $0.016 (99.5% cheaper, recommended fallback)

## Important Files

### Configuration
- `application.properties` - Main config
- `docker-compose.yml` - PostgreSQL + Neo4j
- `config/*.java` - JPA, Neo4j, OpenAPI configs
- `src/main/resources/config/sca-lexicon.yaml` - SCA flavor wheel (YAML-editable)

### Backend
- `entity/` - CoffeeBrand, CoffeeProduct, BrandApproval
- `service/` - CrawlerService, PerplexityApiService, OpenAIService, PlaywrightScraperService, SCAFlavorWheelService, KnowledgeGraphService
- `controller/` - BrandController, ProductController, CrawlerController, KnowledgeGraphController, FlavorWheelController

### Frontend
- `resources/static/*.html` - index, brands, products, flavor-wheel
- `resources/static/js/*.js` - brands, products, flavor-wheel (vanilla JS, no D3.js)
- `resources/static/css/styles.css` - Coffee-themed styling

## API Endpoints Summary

### Brands
- `GET /api/brands/approved` - Approved brands
- `POST /api/brands/submit` - Submit for approval
- `GET /api/brands/generate-list?country={}&limit={}` - AI-powered brand discovery
- `POST /api/brands/bulk-submit` - Bulk submit brands
- `POST /api/brands/auto-setup` - Auto-setup brand by name

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
- Frontend: http://localhost:8080/brands.html
- Neo4j Browser: http://localhost:7474

## Neo4j Knowledge Graph

### Node Types
- **Product** - productId (Long), relationships to all other nodes
- **Brand** - name (String), brand metadata
- **RoastLevel** - level (Light/Medium/Dark/Omni/Unknown)
- **Flavor** - name (lowercase), scaCategory, scaSubcategory
- **Origin** - country, region (core country nodes with region=null always created)
- **Process** - type (Washed, Natural, etc.)
- **Variety** - name (Geisha, Caturra, etc.)
- **Producer** - name, country
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
