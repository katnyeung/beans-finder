# Coffee Database and Knowledge Graph

A centralized coffee product database with SCA flavor wheel-based knowledge graph for discovering and tracking specialty coffee across UK roasters.

## 🎯 Project Overview

This application solves the problem of fragmented, inconsistent coffee product listings across different roaster websites by:

1. **Automated Crawling**: Scheduled crawler that updates product data every 14 days
2. **Data Standardization**: Uses Perplexity API to extract and normalize product information into structured JSON
3. **SCA Flavor Wheel Mapping**: Automatically maps tasting notes to the SCA Coffee Taster's Flavor Wheel categories
4. **Knowledge Graph**: Neo4j-powered graph database for complex queries like "honey-processed Geishas with pear-like sweetness"
5. **Central Database**: PostgreSQL database consolidating products from multiple roasters

## 🏗️ Architecture

- **Backend**: Spring Boot 3.2 with Java 17
- **Relational DB**: PostgreSQL (Neon-compatible)
- **Graph DB**: Neo4j
- **Data Extraction**: Perplexity API + Jsoup web scraping
- **Scheduler**: Spring @Scheduled for biweekly updates

## 📋 Prerequisites

- Java 17 or higher
- Maven 3.6+
- PostgreSQL (or Neon account)
- Neo4j (local or cloud instance)
- Perplexity API key

## 🚀 Setup

### 1. Clone the repository

```bash
git clone https://github.com/yourusername/beans-finder.git
cd beans-finder
```

### 2. Configure environment variables

Create a `.env` file or set environment variables:

```bash
# PostgreSQL (Neon)
DATABASE_URL=postgresql://user:password@your-neon-host.neon.tech/coffee_db
SPRING_DATASOURCE_USERNAME=your_username
SPRING_DATASOURCE_PASSWORD=your_password

# Perplexity API
PERPLEXITY_API_KEY=your_perplexity_api_key

# Neo4j
NEO4J_URI=bolt://localhost:7687
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=your_neo4j_password

# Crawler (optional)
CRAWLER_ENABLED=true
CRAWLER_UPDATE_INTERVAL_DAYS=14
```

### 3. Build the project

```bash
mvn clean install
```

### 4. Run the application

```bash
mvn spring-boot:run
```

Or with dev profile for sample data:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

The application will start on `http://localhost:8080`

## 📊 Database Schema

### PostgreSQL Tables

**coffee_products**
- `id` (BIGINT) - Primary key
- `brand` (VARCHAR) - Coffee roaster name
- `product_name` (VARCHAR) - Product name
- `origin` (VARCHAR) - Country of origin
- `region` (VARCHAR) - Specific region
- `process` (VARCHAR) - Processing method
- `producer` (VARCHAR) - Farm/producer name
- `variety` (VARCHAR) - Coffee variety
- `altitude` (VARCHAR) - Growing altitude
- `tasting_notes` (JSONB) - Array of tasting notes
- `sca_flavors` (JSONB) - SCA wheel mappings
- `seller_url` (VARCHAR) - Product page URL
- `price` (DECIMAL)
- `currency` (VARCHAR) - Default: GBP
- `in_stock` (BOOLEAN)
- `last_update_date` (TIMESTAMP)
- `crawl_status` (VARCHAR) - pending/in_progress/completed/failed
- `raw_description` (TEXT)

### Neo4j Graph Schema

**Nodes:**
- `Product` - Coffee products
- `Origin` - Geographic origins
- `Process` - Processing methods
- `Flavor` - Specific flavor notes
- `SCACategory` - SCA wheel categories
- `Variety` - Coffee varieties

**Relationships:**
- `FROM_ORIGIN` - Product → Origin
- `HAS_PROCESS` - Product → Process
- `HAS_FLAVOR` - Product → Flavor
- `BELONGS_TO_WHEEL` - Flavor → SCACategory
- `OF_VARIETY` - Product → Variety

## 🔌 API Endpoints

### Coffee Products

#### Create/Update Product
```bash
POST /api/coffee
Content-Type: application/json

{
  "brand": "Sweven Coffee",
  "productName": "El Ocaso - Geisha",
  "url": "https://swevencoffee.co.uk/products/el-ocaso-geisha"
}
```

#### Get All Products
```bash
GET /api/coffee
```

#### Get Products by Brand
```bash
GET /api/coffee/brand/Sweven%20Coffee
```

#### Get In-Stock Products
```bash
GET /api/coffee/in-stock
```

#### Search Products
```bash
GET /api/coffee/search?origin=Colombia&process=Honey&variety=Geisha
```

#### Get Outdated Products
```bash
GET /api/coffee/outdated?days=14
```

### Knowledge Graph Queries

#### Find by SCA Category
```bash
GET /api/graph/sca/fruity
```

#### Find by Specific Flavor
```bash
GET /api/graph/flavor/pear
```

#### Find by Variety
```bash
GET /api/graph/variety/Geisha
```

#### Find by Origin
```bash
GET /api/graph/origin/Colombia
```

#### Complex Query: Process + Flavor
```bash
GET /api/graph/query?process=Honey&sca=fruity
```
*Example: Find honey-processed coffees with fruity notes*

### Crawler Operations

#### Trigger Manual Crawl
```bash
POST /api/crawler/trigger
```

## 🔄 Scheduled Crawler

The application includes a scheduler that:
- Runs daily at 2 AM (configurable via `coffee.crawler.cron-schedule`)
- Finds products not updated in 14 days (configurable via `coffee.crawler.update-interval-days`)
- Crawls product pages and updates database
- Updates Neo4j knowledge graph
- Respects rate limits with configurable delays

## 🎨 SCA Flavor Wheel Integration

The system automatically maps tasting notes to SCA categories:

**Categories:**
- Fruity (berry, dried fruit, citrus, stone fruit)
- Floral (jasmine, rose, tea-like)
- Sweet (honey, caramel, vanilla)
- Nutty/Cocoa (chocolate, nuts)
- Spices (cinnamon, pepper)
- Roasted (smoky, tobacco)
- Green/Vegetative
- Sour/Fermented
- Other

**Example Mapping:**
```json
{
  "tasting_notes": ["Nashi pear", "oolong", "cherimoya"],
  "sca_flavors": {
    "fruity": ["Nashi pear", "cherimoya"],
    "floral": ["oolong"]
  }
}
```

## 🧪 Example Usage

### 1. Add a Coffee Product

```bash
curl -X POST http://localhost:8080/api/coffee \
  -H "Content-Type: application/json" \
  -d '{
    "brand": "Sweven Coffee",
    "productName": "El Ocaso - Geisha",
    "url": "https://swevencoffee.co.uk/products/el-ocaso-geisha"
  }'
```

The system will:
1. Scrape the product page
2. Extract structured data via Perplexity API
3. Map tasting notes to SCA categories
4. Store in PostgreSQL
5. Create nodes in Neo4j knowledge graph

### 2. Query Knowledge Graph

Find all Geisha coffees with fruity notes:

```bash
curl "http://localhost:8080/api/graph/query?process=Honey&sca=fruity"
```

### 3. Search by Origin

```bash
curl "http://localhost:8080/api/coffee/search?origin=Colombia"
```

## 🛠️ Configuration

All configuration is in `src/main/resources/application.yml`:

```yaml
coffee:
  perplexity:
    api-key: ${PERPLEXITY_API_KEY}
    model: llama-3.1-sonar-large-128k-online

  crawler:
    enabled: true
    delay-seconds: 2
    retry-attempts: 3
    update-interval-days: 14
    cron-schedule: "0 0 2 * * ?"  # Daily at 2 AM
```

## 📈 Scaling to Production

1. **Database**: Migrate to managed PostgreSQL (Neon, AWS RDS)
2. **Neo4j**: Use Neo4j AuraDB for managed graph database
3. **Scheduler**: Deploy to cloud (AWS Lambda, Google Cloud Run) with cron triggers
4. **Rate Limiting**: Implement Redis-based rate limiting
5. **Monitoring**: Add Prometheus metrics and Grafana dashboards
6. **Authentication**: Add Spring Security with JWT

## 🤝 Contributing

Contributions welcome! Areas for improvement:

- Add more roaster integrations
- Improve SCA flavor mapping with ML
- Add price tracking and alerts
- Build frontend UI
- Add product recommendations based on taste preferences

## 📝 License

MIT License

## 🙏 Acknowledgments

- [SCA Coffee Taster's Flavor Wheel](https://sca.coffee/)
- [Perplexity API](https://docs.perplexity.ai/)
- Sweven Coffee and other UK specialty roasters

## 📧 Contact

For questions or feedback, please open an issue on GitHub.
