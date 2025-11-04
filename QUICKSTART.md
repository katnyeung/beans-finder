# Quick Start Guide

Get the Coffee Database and Knowledge Graph up and running in 5 minutes!

## Option 1: Docker (Recommended for Testing)

### Prerequisites
- Docker and Docker Compose installed
- Perplexity API key

### Steps

1. **Start databases**
   ```bash
   docker-compose up -d
   ```
   This starts PostgreSQL on port 5432 and Neo4j on ports 7474 (HTTP) and 7687 (Bolt).

2. **Set environment variables**
   ```bash
   export PERPLEXITY_API_KEY="your_api_key_here"
   export DATABASE_URL="jdbc:postgresql://localhost:5432/coffee_db"
   export SPRING_DATASOURCE_USERNAME="postgres"
   export SPRING_DATASOURCE_PASSWORD="postgres"
   export NEO4J_URI="bolt://localhost:7687"
   export NEO4J_USERNAME="neo4j"
   export NEO4J_PASSWORD="password"
   ```

3. **Run the application**
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=dev
   ```

4. **Test the API**
   ```bash
   # Create a coffee product
   curl -X POST http://localhost:8080/api/coffee \
     -H "Content-Type: application/json" \
     -d '{
       "brand": "Sweven Coffee",
       "productName": "El Ocaso - Geisha",
       "url": "https://swevencoffee.co.uk/products/el-ocaso-geisha"
     }'
   ```

5. **Access Neo4j Browser**
   - Open http://localhost:7474
   - Login with username: `neo4j`, password: `password`
   - Run query: `MATCH (n) RETURN n LIMIT 25`

## Option 2: Neon + Managed Neo4j

### Prerequisites
- Neon account (free tier available)
- Neo4j AuraDB account (free tier available)
- Perplexity API key

### Steps

1. **Create Neon database**
   - Go to https://neon.tech
   - Create new project
   - Copy connection string

2. **Create Neo4j AuraDB instance**
   - Go to https://neo4j.com/cloud/aura/
   - Create free instance
   - Copy connection details

3. **Create .env file**
   ```bash
   DATABASE_URL=postgresql://user:pass@your-neon-host.neon.tech/main
   SPRING_DATASOURCE_USERNAME=your_username
   SPRING_DATASOURCE_PASSWORD=your_password

   PERPLEXITY_API_KEY=your_perplexity_key

   NEO4J_URI=neo4j+s://your-instance.databases.neo4j.io
   NEO4J_USERNAME=neo4j
   NEO4J_PASSWORD=your_neo4j_password
   ```

4. **Run the application**
   ```bash
   mvn spring-boot:run
   ```

## Testing the System

### 1. Add Sample Products

Run the example script:
```bash
chmod +x examples/api-examples.sh
./examples/api-examples.sh
```

### 2. Manual Product Addition

Add a product:
```bash
curl -X POST http://localhost:8080/api/coffee \
  -H "Content-Type: application/json" \
  -d '{
    "brand": "Pact Coffee",
    "productName": "Colombian Single Origin",
    "url": "https://www.pactcoffee.com/coffees/colombia"
  }'
```

Wait a few seconds for processing, then retrieve it:
```bash
curl http://localhost:8080/api/coffee/brand/Pact%20Coffee | jq '.'
```

### 3. Query Knowledge Graph

Find fruity coffees:
```bash
curl http://localhost:8080/api/graph/sca/fruity | jq '.'
```

Find Geisha varieties:
```bash
curl http://localhost:8080/api/graph/variety/Geisha | jq '.'
```

Complex query (honey-processed + fruity):
```bash
curl "http://localhost:8080/api/graph/query?process=Honey&sca=fruity" | jq '.'
```

### 4. Trigger Manual Crawl

```bash
curl -X POST http://localhost:8080/api/crawler/trigger
```

## Troubleshooting

### Issue: "Connection refused" to PostgreSQL
- Check Docker is running: `docker ps`
- Verify port 5432 is not in use: `lsof -i :5432`

### Issue: "Connection refused" to Neo4j
- Check Neo4j is running: `docker logs coffee-neo4j`
- Verify port 7687 is not in use: `lsof -i :7687`

### Issue: "Perplexity API failed"
- Verify API key is set: `echo $PERPLEXITY_API_KEY`
- Check API quota/limits at Perplexity dashboard

### Issue: "Failed to extract data"
- Check if URL is accessible
- Verify robots.txt allows crawling
- Try with a different coffee product URL

## Next Steps

1. **Add More Products**: Add your favorite UK roasters
2. **Explore the Graph**: Use Neo4j Browser to visualize relationships
3. **Custom Queries**: Write custom Cypher queries in Neo4j
4. **Schedule Crawling**: Enable the scheduler in production
5. **Build a Frontend**: Create a web UI for product discovery

## Useful Neo4j Queries

```cypher
// Find all products with their flavors
MATCH (p:Product)-[:HAS_FLAVOR]->(f:Flavor)
RETURN p.name, f.specific, f.scaCategory

// Find products by origin and process
MATCH (p:Product)-[:FROM_ORIGIN]->(o:Origin),
      (p)-[:HAS_PROCESS]->(pr:Process)
WHERE o.country = 'Colombia' AND pr.type = 'Honey'
RETURN p

// Find flavor connections
MATCH (f:Flavor)-[:BELONGS_TO_WHEEL]->(s:SCACategory)
RETURN f.specific, s.category, COUNT(*) as occurrences
ORDER BY occurrences DESC
```

## Resources

- [Perplexity API Docs](https://docs.perplexity.ai/)
- [Neo4j Cypher Manual](https://neo4j.com/docs/cypher-manual/current/)
- [SCA Flavor Wheel](https://sca.coffee/research/coffee-tasters-flavor-wheel)
- [Spring Boot Docs](https://spring.io/projects/spring-boot)
