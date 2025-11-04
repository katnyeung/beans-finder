# IntelliJ IDEA Setup Guide

## Quick Start (Recommended)

### 1. Use application-dev.properties

The easiest way to configure your development environment:

1. **Copy the template:**
   ```bash
   cp src/main/resources/application-dev.properties.example src/main/resources/application-dev.properties
   ```

2. **Edit `application-dev.properties` with your actual values:**
   ```properties
   spring.datasource.url=jdbc:postgresql://localhost:5432/coffee_db
   spring.datasource.username=postgres
   spring.datasource.password=postgres
   perplexity.api.key=your_actual_api_key_here
   ```

3. **Run with dev profile in IntelliJ:**
   - Go to `Run` → `Edit Configurations`
   - Select your Spring Boot configuration
   - In "Active profiles" field, enter: `dev`
   - Click Apply and OK

That's it! IntelliJ will automatically use `application-dev.properties` when running with the `dev` profile.

---

## Alternative Methods

### Option A: EnvFile Plugin

1. **Install Plugin:**
   - `File` → `Settings` → `Plugins`
   - Search "EnvFile" and install
   - Restart IntelliJ

2. **Configure:**
   - `Run` → `Edit Configurations`
   - Go to `EnvFile` tab
   - Click `+` → Add `.env` file
   - Enable the file

### Option B: Manual Environment Variables

1. **Edit Run Configuration:**
   - `Run` → `Edit Configurations`
   - Find "Environment variables" field
   - Click folder icon
   - Add variables:
     ```
     DATABASE_URL=jdbc:postgresql://localhost:5432/coffee_db;
     SPRING_DATASOURCE_USERNAME=postgres;
     SPRING_DATASOURCE_PASSWORD=postgres;
     PERPLEXITY_API_KEY=your_key_here;
     NEO4J_URI=bolt://localhost:7687;
     NEO4J_USER=neo4j;
     NEO4J_PASSWORD=password
     ```

---

## IntelliJ Configuration Checklist

### 1. JDK Setup
- `File` → `Project Structure` → `Project`
- Set SDK to Java 17 or higher
- Set language level to 17

### 2. Maven Configuration
- Enable auto-import: `File` → `Settings` → `Build, Execution, Deployment` → `Build Tools` → `Maven`
- Check "Reload project after changes in build files"

### 3. Lombok Plugin
- `File` → `Settings` → `Plugins`
- Search and install "Lombok"
- Enable annotation processing:
  - `Settings` → `Build, Execution, Deployment` → `Compiler` → `Annotation Processors`
  - Check "Enable annotation processing"

### 4. Spring Boot Configuration
IntelliJ should auto-detect the Spring Boot application. If not:
- Right-click `BeansFinderApplication.java`
- Click "Run 'BeansFinderApplication'"

---

## Starting Databases

Before running the application:

```bash
# Start PostgreSQL and Neo4j
docker-compose up -d

# Verify they're running
docker ps

# Check logs if needed
docker logs coffee-postgres
docker logs coffee-neo4j
```

---

## Run Configuration Example

Here's what your IntelliJ Run Configuration should look like:

**Configuration Type:** Spring Boot

**Main class:** `com.coffee.beansfinder.BeansFinderApplication`

**Active profiles:** `dev`

**Environment variables:** (if not using application-dev.properties)
```
DATABASE_URL=jdbc:postgresql://localhost:5432/coffee_db;
SPRING_DATASOURCE_USERNAME=postgres;
SPRING_DATASOURCE_PASSWORD=postgres;
PERPLEXITY_API_KEY=your_key;
NEO4J_URI=bolt://localhost:7687;
NEO4J_USER=neo4j;
NEO4J_PASSWORD=password
```

**VM options:** (optional, for more memory)
```
-Xmx2048m
```

---

## Debugging

### Enable Debug Logging

Add to `application-dev.properties`:
```properties
logging.level.com.coffee=DEBUG
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
```

### Debug Run Configuration
- Click the bug icon instead of run icon
- Set breakpoints in your code
- Use "Evaluate Expression" (Alt+F8) to inspect variables

---

## Common Issues

### Issue: "Cannot resolve symbol"
**Solution:** Rebuild project
- `Build` → `Rebuild Project`
- Or: `mvn clean compile` in terminal

### Issue: "Port 8080 already in use"
**Solution:** Kill process or change port
```bash
# Find process
lsof -i :8080

# Kill it
kill -9 <PID>

# Or change port in application-dev.properties
server.port=8081
```

### Issue: Database connection refused
**Solution:** Check Docker is running
```bash
docker ps
docker-compose up -d
```

### Issue: Lombok not working
**Solution:**
1. Install Lombok plugin
2. Enable annotation processing in settings
3. Invalidate caches: `File` → `Invalidate Caches / Restart`

---

## Hot Reload / DevTools

Add Spring Boot DevTools for automatic restart on code changes:

Already included in `pom.xml` for development. IntelliJ will automatically detect changes and restart the application.

To enable automatic compilation:
1. `Settings` → `Build, Execution, Deployment` → `Compiler`
2. Check "Build project automatically"
3. Press `Ctrl+Shift+A` → Search "Registry"
4. Enable `compiler.automake.allow.when.app.running`

---

## Running Tests

Right-click on:
- `src/test/java` folder → "Run 'All Tests'"
- Individual test class → "Run 'TestClassName'"
- Individual test method → "Run 'testMethodName'"

Or use Maven:
```bash
mvn test
```

---

## Database Tools in IntelliJ

IntelliJ Ultimate has built-in database tools:

1. **Open Database Tool Window:** `View` → `Tool Windows` → `Database`
2. **Add PostgreSQL:**
   - Click `+` → `Data Source` → `PostgreSQL`
   - Host: `localhost`
   - Port: `5432`
   - Database: `coffee_db`
   - User: `postgres`
   - Password: `postgres`
   - Test Connection → Apply

3. **Query Console:**
   - Right-click database → `New` → `Query Console`
   - Run SQL queries directly

---

## Useful Keyboard Shortcuts

- **Run:** `Shift+F10`
- **Debug:** `Shift+F9`
- **Stop:** `Ctrl+F2`
- **Rerun:** `Ctrl+F5`
- **Toggle Breakpoint:** `Ctrl+F8`
- **Evaluate Expression:** `Alt+F8`
- **Find Class:** `Ctrl+N`
- **Find File:** `Ctrl+Shift+N`
- **Find in Files:** `Ctrl+Shift+F`
- **Refactor/Rename:** `Shift+F6`

---

## Next Steps

1. Start databases: `docker-compose up -d`
2. Configure `application-dev.properties`
3. Run application in IntelliJ
4. Test: http://localhost:8080/api/brands
5. Check Neo4j Browser: http://localhost:7474

Happy coding! 🚀
