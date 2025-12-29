package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.graph.repository.AttributeNodeRepository;
import com.coffee.beansfinder.graph.repository.TastingNoteNodeRepository;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.coffee.beansfinder.service.DiscoverCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for Discover page - live queries for drill-down interactions
 */
@RestController
@RequestMapping("/api/discover")
@Tag(name = "Discover", description = "Discover page endpoints - live queries for interactive features")
@Slf4j
public class DiscoverController {

    private final CoffeeProductRepository productRepository;
    private final DiscoverCacheService discoverCacheService;
    private final Neo4jClient neo4jClient;
    private final AttributeNodeRepository attributeNodeRepository;
    private final TastingNoteNodeRepository tastingNoteNodeRepository;

    public DiscoverController(CoffeeProductRepository productRepository,
                               DiscoverCacheService discoverCacheService,
                               Neo4jClient neo4jClient,
                               AttributeNodeRepository attributeNodeRepository,
                               TastingNoteNodeRepository tastingNoteNodeRepository) {
        this.productRepository = productRepository;
        this.discoverCacheService = discoverCacheService;
        this.neo4jClient = neo4jClient;
        this.attributeNodeRepository = attributeNodeRepository;
        this.tastingNoteNodeRepository = tastingNoteNodeRepository;
    }

    /**
     * Get products from a specific origin (country)
     */
    @GetMapping("/origin/{country}/products")
    @Operation(summary = "Get products by origin",
               description = "Returns products from a specific coffee origin country")
    public ResponseEntity<List<Map<String, Object>>> getProductsByOrigin(
            @PathVariable String country,
            @RequestParam(defaultValue = "20") int limit) {

        log.info("Fetching products for origin: {}", country);

        List<CoffeeProduct> products = productRepository.findByOriginIgnoreCase(country);

        List<Map<String, Object>> result = products.stream()
                .limit(limit)
                .map(this::mapProductToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * Get products with a specific process
     */
    @GetMapping("/process/{processType}/products")
    @Operation(summary = "Get products by process",
               description = "Returns products with a specific processing method")
    public ResponseEntity<List<Map<String, Object>>> getProductsByProcess(
            @PathVariable String processType,
            @RequestParam(defaultValue = "20") int limit) {

        log.info("Fetching products for process: {}", processType);

        // Use database query instead of loading all products into memory
        List<CoffeeProduct> products = productRepository.findActiveByProcessContainingWithBrand(processType);

        List<Map<String, Object>> result = products.stream()
                .limit(limit)
                .map(this::mapProductToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * Get products with a specific variety
     */
    @GetMapping("/variety/{varietyName}/products")
    @Operation(summary = "Get products by variety",
               description = "Returns products with a specific coffee variety")
    public ResponseEntity<List<Map<String, Object>>> getProductsByVariety(
            @PathVariable String varietyName,
            @RequestParam(defaultValue = "20") int limit) {

        log.info("Fetching products for variety: {}", varietyName);

        // Use database query instead of loading all products into memory
        List<CoffeeProduct> products = productRepository.findActiveByVarietyContainingWithBrand(varietyName);

        List<Map<String, Object>> result = products.stream()
                .limit(limit)
                .map(this::mapProductToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * Get products with a specific roast level
     */
    @GetMapping("/roast/{roastLevel}/products")
    @Operation(summary = "Get products by roast level",
               description = "Returns products with a specific roast level")
    public ResponseEntity<List<Map<String, Object>>> getProductsByRoastLevel(
            @PathVariable String roastLevel,
            @RequestParam(defaultValue = "20") int limit) {

        log.info("Fetching products for roast level: {}", roastLevel);

        // Use database query instead of loading all products into memory
        List<CoffeeProduct> products = productRepository.findActiveByRoastLevelWithBrand(roastLevel);

        List<Map<String, Object>> result = products.stream()
                .limit(limit)
                .map(this::mapProductToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * Get products with BOTH flavors (for matrix cell click)
     */
    @GetMapping("/flavor-pair/{flavor1}/{flavor2}/products")
    @Operation(summary = "Get products by flavor pair",
               description = "Returns products that have BOTH specified flavors (for co-occurrence matrix)")
    public ResponseEntity<List<Map<String, Object>>> getProductsByFlavorPair(
            @PathVariable String flavor1,
            @PathVariable String flavor2,
            @RequestParam(defaultValue = "20") int limit) {

        log.info("Fetching products with flavors: {} + {}", flavor1, flavor2);

        String normalizedF1 = flavor1.toLowerCase().trim();
        String normalizedF2 = flavor2.toLowerCase().trim();

        // Use Neo4j to find products with both flavors (exclude deleted)
        // Collect all origins for products with multiple origins (blends)
        String cypher = """
            MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn1:TastingNote {id: $flavor1})
            MATCH (p)-[:HAS_TASTING_NOTE]->(tn2:TastingNote {id: $flavor2})
            WHERE p.deleted IS NULL OR p.deleted = false
            OPTIONAL MATCH (p)-[:SOLD_BY]->(b:Brand)
            OPTIONAL MATCH (p)-[:FROM_ORIGIN]->(o:Origin)
            WITH p, b, COLLECT(DISTINCT o.country) as origins
            RETURN DISTINCT p.productId as productId, p.productName as productName,
                   b.name as brandName, origins,
                   p.roastLevel as roastLevel, p.price as price
            LIMIT $limit
            """;

        Collection<Map<String, Object>> neo4jResults = neo4jClient.query(cypher)
                .bind(normalizedF1).to("flavor1")
                .bind(normalizedF2).to("flavor2")
                .bind(limit).to("limit")
                .fetch()
                .all();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : neo4jResults) {
            Map<String, Object> product = new HashMap<>();
            product.put("id", row.get("productId"));
            product.put("productName", row.get("productName"));
            product.put("brandName", row.get("brandName"));
            // Origins is now a list - join with " / " for display
            @SuppressWarnings("unchecked")
            List<String> origins = (List<String>) row.get("origins");
            if (origins != null && !origins.isEmpty()) {
                String originStr = origins.stream()
                        .filter(o -> o != null)
                        .collect(Collectors.joining(" / "));
                product.put("origin", originStr);
            } else {
                product.put("origin", null);
            }
            product.put("roastLevel", row.get("roastLevel"));
            product.put("price", row.get("price"));
            result.add(product);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get products similar to a specific flavor
     */
    @GetMapping("/flavor/{flavorName}/similar")
    @Operation(summary = "Get products by flavor",
               description = "Returns products with a specific flavor note")
    public ResponseEntity<List<Map<String, Object>>> getProductsByFlavor(
            @PathVariable String flavorName,
            @RequestParam(defaultValue = "20") int limit) {

        log.info("Fetching products with flavor: {}", flavorName);

        String normalizedFlavor = flavorName.toLowerCase().trim();

        // Use Neo4j to find products with this flavor (exclude deleted)
        // Collect all origins for products with multiple origins (blends)
        String cypher = """
            MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote {id: $flavor})
            WHERE p.deleted IS NULL OR p.deleted = false
            OPTIONAL MATCH (p)-[:SOLD_BY]->(b:Brand)
            OPTIONAL MATCH (p)-[:FROM_ORIGIN]->(o:Origin)
            WITH p, b, COLLECT(DISTINCT o.country) as origins
            RETURN DISTINCT p.productId as productId, p.productName as productName,
                   b.name as brandName, origins,
                   p.roastLevel as roastLevel, p.price as price
            LIMIT $limit
            """;

        Collection<Map<String, Object>> neo4jResults = neo4jClient.query(cypher)
                .bind(normalizedFlavor).to("flavor")
                .bind(limit).to("limit")
                .fetch()
                .all();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : neo4jResults) {
            Map<String, Object> product = new HashMap<>();
            product.put("id", row.get("productId"));
            product.put("productName", row.get("productName"));
            product.put("brandName", row.get("brandName"));
            // Origins is now a list - join with " / " for display
            @SuppressWarnings("unchecked")
            List<String> origins = (List<String>) row.get("origins");
            if (origins != null && !origins.isEmpty()) {
                String originStr = origins.stream()
                        .filter(o -> o != null)
                        .collect(Collectors.joining(" / "));
                product.put("origin", originStr);
            } else {
                product.put("origin", null);
            }
            product.put("roastLevel", row.get("roastLevel"));
            product.put("price", row.get("price"));
            result.add(product);
        }

        return ResponseEntity.ok(result);
    }

    // ==================== FLAVOR EXPLORER ENDPOINTS ====================

    /**
     * Get SCA flavor categories with product counts
     */
    @GetMapping("/flavor-explorer/categories")
    @Operation(summary = "Get flavor categories",
               description = "Returns 9 SCA flavor categories with product counts")
    public ResponseEntity<List<Map<String, Object>>> getFlavorCategories() {
        log.info("Fetching flavor categories");

        String cypher = """
            MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote)
            WHERE tn.scaCategoryName IS NOT NULL
            AND (p.deleted IS NULL OR p.deleted = false)
            WITH tn.scaCategoryName as category, count(DISTINCT p) as productCount
            RETURN category, productCount
            ORDER BY productCount DESC
            """;

        Collection<Map<String, Object>> neo4jResults = neo4jClient.query(cypher)
                .fetch()
                .all();

        // Map category names to emojis
        Map<String, String> categoryEmojis = Map.of(
                "fruity", "🍎",
                "floral", "🌸",
                "sweet", "🍯",
                "roasted", "☕",
                "nutty/cocoa", "🥜",
                "spices", "🌶️",
                "green/vegetative", "🌿",
                "sour/fermented", "🍋",
                "other", "✨"
        );

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : neo4jResults) {
            String category = (String) row.get("category");
            if (category != null) {
                Map<String, Object> cat = new HashMap<>();
                cat.put("name", category);
                cat.put("productCount", row.get("productCount"));
                cat.put("emoji", categoryEmojis.getOrDefault(category.toLowerCase(), "🔘"));
                result.add(cat);
            }
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get flavors within a specific SCA category
     */
    @GetMapping("/flavor-explorer/category/{categoryName}")
    @Operation(summary = "Get flavors in category",
               description = "Returns subcategories and flavors within an SCA category")
    public ResponseEntity<Map<String, Object>> getCategoryFlavors(
            @PathVariable String categoryName) {

        log.info("Fetching flavors for category: {}", categoryName);

        String cypher = """
            MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote)
            WHERE toLower(tn.scaCategoryName) = toLower($category)
            AND (p.deleted IS NULL OR p.deleted = false)
            WITH tn.id as flavorId, tn.rawText as flavorName,
                 count(DISTINCT p) as productCount
            RETURN flavorId, flavorName, productCount
            ORDER BY productCount DESC
            """;

        Collection<Map<String, Object>> neo4jResults = neo4jClient.query(cypher)
                .bind(categoryName).to("category")
                .fetch()
                .all();

        List<Map<String, Object>> allFlavors = new ArrayList<>();

        for (Map<String, Object> row : neo4jResults) {
            Map<String, Object> flavor = new HashMap<>();
            flavor.put("id", row.get("flavorId"));
            flavor.put("name", row.get("flavorName"));
            flavor.put("productCount", row.get("productCount"));
            allFlavors.add(flavor);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("category", categoryName);
        result.put("allFlavors", allFlavors);
        result.put("totalFlavors", allFlavors.size());

        return ResponseEntity.ok(result);
    }

    /**
     * Level 2: Get Attributes within a specific SCA category
     * Shows normalized ~110 attributes instead of thousands of raw tasting notes
     */
    @GetMapping("/flavor-explorer/category/{categoryName}/attributes")
    @Operation(summary = "Get attributes in category (Level 2)",
               description = "Returns normalized Attributes (~110) within an SCA category with product counts")
    public ResponseEntity<Map<String, Object>> getCategoryAttributes(
            @PathVariable String categoryName) {

        log.info("Fetching attributes for category: {}", categoryName);

        List<Map<String, Object>> attributeData = attributeNodeRepository.findByCategoryNameWithCounts(categoryName.toLowerCase());

        List<Map<String, Object>> attributes = new ArrayList<>();
        for (Map<String, Object> row : attributeData) {
            Map<String, Object> attr = new HashMap<>();
            attr.put("id", row.get("attributeId"));
            attr.put("name", row.get("attributeName"));
            attr.put("subcategory", row.get("subcategory"));
            attr.put("productCount", row.get("productCount"));
            attributes.add(attr);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("category", categoryName);
        result.put("attributes", attributes);
        result.put("totalAttributes", attributes.size());

        return ResponseEntity.ok(result);
    }

    /**
     * Level 3: Get TastingNotes that MATCH a specific Attribute
     * Shows all raw variations (e.g., "cherry", "maraschino cherry", "cherry blossom")
     */
    @GetMapping("/flavor-explorer/attribute/{attributeId}/notes")
    @Operation(summary = "Get tasting notes for attribute (Level 3)",
               description = "Returns all TastingNotes that MATCH the given Attribute with product counts")
    public ResponseEntity<Map<String, Object>> getAttributeNotes(
            @PathVariable String attributeId) {

        log.info("Fetching tasting notes for attribute: {}", attributeId);

        List<Map<String, Object>> noteData = tastingNoteNodeRepository.findByAttributeIdWithCounts(attributeId.toLowerCase());

        List<Map<String, Object>> notes = new ArrayList<>();
        for (Map<String, Object> row : noteData) {
            Map<String, Object> note = new HashMap<>();
            note.put("id", row.get("noteId"));
            note.put("name", row.get("rawText"));
            note.put("productCount", row.get("productCount"));
            notes.add(note);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("attributeId", attributeId);
        result.put("notes", notes);
        result.put("totalNotes", notes.size());

        return ResponseEntity.ok(result);
    }

    /**
     * Drill down into flavors - find related flavors and products
     * Uses Redis caching for performance
     */
    @PostMapping("/flavor-explorer/drilldown")
    @Operation(summary = "Flavor drilldown",
               description = "Returns related flavors and products matching ALL selected flavors (cached)")
    public ResponseEntity<Map<String, Object>> flavorDrilldown(
            @RequestBody Map<String, Object> request) {

        @SuppressWarnings("unchecked")
        List<String> flavors = (List<String>) request.getOrDefault("flavors", Collections.emptyList());
        int limit = (int) request.getOrDefault("limit", 20);

        log.info("Flavor drilldown for: {}", flavors);

        if (flavors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No flavors provided"));
        }

        // Use cached service method
        Map<String, Object> result = discoverCacheService.getFlavorDrilldown(flavors, limit);

        return ResponseEntity.ok(result);
    }

    /**
     * Get a random rare flavor for discovery
     */
    @GetMapping("/flavor-explorer/random-rare")
    @Operation(summary = "Get random rare flavor",
               description = "Returns a random flavor with few products for discovery")
    public ResponseEntity<Map<String, Object>> getRandomRareFlavor() {
        log.info("Fetching random rare flavor");

        String cypher = """
            MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote)
            WHERE p.deleted IS NULL OR p.deleted = false
            WITH tn, count(DISTINCT p) as productCount
            WHERE productCount >= 2 AND productCount <= 5
            WITH tn, productCount, rand() as r
            ORDER BY r
            LIMIT 1
            RETURN tn.id as flavorId, tn.rawText as flavorName,
                   tn.scaCategoryName as category,
                   productCount
            """;

        Collection<Map<String, Object>> neo4jResults = neo4jClient.query(cypher)
                .fetch()
                .all();

        if (neo4jResults.isEmpty()) {
            return ResponseEntity.ok(Map.of("error", "No rare flavors found"));
        }

        Map<String, Object> row = neo4jResults.iterator().next();
        Map<String, Object> result = new HashMap<>();
        result.put("id", row.get("flavorId"));
        result.put("name", row.get("flavorName"));
        result.put("category", row.get("category"));
        result.put("productCount", row.get("productCount"));

        return ResponseEntity.ok(result);
    }

    // ==================== CONNECTED FILTER ENDPOINTS ====================

    /**
     * Get filter options with counts - independent filters
     * Each filter shows counts based on ALL other active filters
     *
     * NOTE: In Neo4j, these are stored as relationships:
     * - Process: (p)-[:HAS_PROCESS]->(proc:Process)
     * - Origin: (p)-[:FROM_ORIGIN]->(o:Origin)
     * - Variety: (p)-[:HAS_VARIETY]->(v:Variety)
     * - Roast: (p)-[:ROASTED_AT]->(r:RoastLevel)
     */
    @GetMapping("/filter-options")
    @Operation(summary = "Get filter options with counts",
               description = "Returns available options for each filter dimension with product counts based on all other active filters")
    public ResponseEntity<Map<String, Object>> getFilterOptions(
            @RequestParam(required = false) String process,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String variety,
            @RequestParam(required = false) String roast) {

        log.info("Getting filter options - process={}, origin={}, variety={}, roast={}", process, origin, variety, roast);

        Map<String, Object> result = new HashMap<>();

        // Get process options (filtered by origin, variety, roast - NOT by process itself)
        StringBuilder processWhere = new StringBuilder("WHERE (p.deleted IS NULL OR p.deleted = false)");
        Map<String, Object> processParams = new HashMap<>();
        if (origin != null && !origin.isEmpty()) {
            // Support comma-separated origins (OR logic)
            List<String> origins = Arrays.stream(origin.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.toList());
            if (origins.size() == 1) {
                processWhere.append(" AND EXISTS { (p)-[:FROM_ORIGIN]->(o:Origin) WHERE toLower(o.country) = $origin }");
                processParams.put("origin", origins.get(0));
            } else {
                processWhere.append(" AND EXISTS { (p)-[:FROM_ORIGIN]->(o:Origin) WHERE toLower(o.country) IN $origins }");
                processParams.put("origins", origins);
            }
        }
        if (variety != null && !variety.isEmpty()) {
            processWhere.append(" AND EXISTS { (p)-[:HAS_VARIETY]->(v:Variety) WHERE toLower(v.name) CONTAINS toLower($variety) }");
            processParams.put("variety", variety);
        }
        if (roast != null && !roast.isEmpty()) {
            processWhere.append(" AND EXISTS { (p)-[:ROASTED_AT]->(r:RoastLevel) WHERE r.level = $roast }");
            processParams.put("roast", roast);
        }
        String processCypher = String.format("""
            MATCH (p:Product)-[:HAS_PROCESS]->(proc:Process)
            %s
            WITH proc.type as processName, count(DISTINCT p) as cnt
            RETURN processName, cnt
            ORDER BY cnt DESC
            """, processWhere.toString());
        List<Map<String, Object>> processes = executeCountQuery(processCypher, processParams, "processName", "cnt");
        processes = normalizeProcessCounts(processes);
        result.put("processes", processes);

        // Get origin options (filtered by process, variety, roast - NOT by origin itself)
        StringBuilder originWhere = new StringBuilder("WHERE (p.deleted IS NULL OR p.deleted = false)");
        Map<String, Object> originParams = new HashMap<>();
        if (process != null && !process.isEmpty()) {
            originWhere.append(" AND EXISTS { (p)-[:HAS_PROCESS]->(proc:Process) WHERE toLower(proc.type) CONTAINS toLower($process) }");
            originParams.put("process", process);
        }
        if (variety != null && !variety.isEmpty()) {
            originWhere.append(" AND EXISTS { (p)-[:HAS_VARIETY]->(v:Variety) WHERE toLower(v.name) CONTAINS toLower($variety) }");
            originParams.put("variety", variety);
        }
        if (roast != null && !roast.isEmpty()) {
            originWhere.append(" AND EXISTS { (p)-[:ROASTED_AT]->(r:RoastLevel) WHERE r.level = $roast }");
            originParams.put("roast", roast);
        }
        String originCypher = String.format("""
            MATCH (p:Product)-[:FROM_ORIGIN]->(o:Origin)
            %s
            WITH o.country as originName, count(DISTINCT p) as cnt
            RETURN originName, cnt
            ORDER BY cnt DESC
            """, originWhere.toString());
        List<Map<String, Object>> origins = executeCountQuery(originCypher, originParams, "originName", "cnt");
        result.put("origins", origins);

        // Get variety options (filtered by process, origin, roast - NOT by variety itself)
        StringBuilder varietyWhere = new StringBuilder("WHERE (p.deleted IS NULL OR p.deleted = false)");
        Map<String, Object> varietyParams = new HashMap<>();
        if (process != null && !process.isEmpty()) {
            varietyWhere.append(" AND EXISTS { (p)-[:HAS_PROCESS]->(proc:Process) WHERE toLower(proc.type) CONTAINS toLower($process) }");
            varietyParams.put("process", process);
        }
        if (origin != null && !origin.isEmpty()) {
            // Support comma-separated origins (OR logic)
            List<String> varietyOrigins = Arrays.stream(origin.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.toList());
            if (varietyOrigins.size() == 1) {
                varietyWhere.append(" AND EXISTS { (p)-[:FROM_ORIGIN]->(o:Origin) WHERE toLower(o.country) = $origin }");
                varietyParams.put("origin", varietyOrigins.get(0));
            } else {
                varietyWhere.append(" AND EXISTS { (p)-[:FROM_ORIGIN]->(o:Origin) WHERE toLower(o.country) IN $origins }");
                varietyParams.put("origins", varietyOrigins);
            }
        }
        if (roast != null && !roast.isEmpty()) {
            varietyWhere.append(" AND EXISTS { (p)-[:ROASTED_AT]->(r:RoastLevel) WHERE r.level = $roast }");
            varietyParams.put("roast", roast);
        }
        String varietyCypher = String.format("""
            MATCH (p:Product)-[:HAS_VARIETY]->(v:Variety)
            %s
            WITH v.name as varietyName, count(DISTINCT p) as cnt
            RETURN varietyName, cnt
            ORDER BY cnt DESC
            """, varietyWhere.toString());
        List<Map<String, Object>> varieties = executeCountQuery(varietyCypher, varietyParams, "varietyName", "cnt");
        result.put("varieties", varieties);

        // Get roast options (filtered by process, origin, variety - NOT by roast itself)
        StringBuilder roastWhere = new StringBuilder("WHERE (p.deleted IS NULL OR p.deleted = false)");
        Map<String, Object> roastParams = new HashMap<>();
        if (process != null && !process.isEmpty()) {
            roastWhere.append(" AND EXISTS { (p)-[:HAS_PROCESS]->(proc:Process) WHERE toLower(proc.type) CONTAINS toLower($process) }");
            roastParams.put("process", process);
        }
        if (origin != null && !origin.isEmpty()) {
            // Support comma-separated origins (OR logic)
            List<String> originsList = Arrays.stream(origin.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.toList());
            if (originsList.size() == 1) {
                roastWhere.append(" AND EXISTS { (p)-[:FROM_ORIGIN]->(o:Origin) WHERE toLower(o.country) = $origin }");
                roastParams.put("origin", originsList.get(0));
            } else {
                roastWhere.append(" AND EXISTS { (p)-[:FROM_ORIGIN]->(o:Origin) WHERE toLower(o.country) IN $origins }");
                roastParams.put("origins", originsList);
            }
        }
        if (variety != null && !variety.isEmpty()) {
            roastWhere.append(" AND EXISTS { (p)-[:HAS_VARIETY]->(v:Variety) WHERE toLower(v.name) CONTAINS toLower($variety) }");
            roastParams.put("variety", variety);
        }
        String roastCypher = String.format("""
            MATCH (p:Product)-[:ROASTED_AT]->(r:RoastLevel)
            %s
            WITH r.level as roastName, count(DISTINCT p) as cnt
            RETURN roastName, cnt
            ORDER BY cnt DESC
            """, roastWhere.toString());
        List<Map<String, Object>> roasts = executeCountQuery(roastCypher, roastParams, "roastName", "cnt");
        result.put("roasts", roasts);

        // Get total product count with ALL filters
        StringBuilder totalWhere = new StringBuilder("WHERE (p.deleted IS NULL OR p.deleted = false)");
        Map<String, Object> totalParams = new HashMap<>();

        if (process != null && !process.isEmpty()) {
            totalWhere.append(" AND EXISTS { (p)-[:HAS_PROCESS]->(proc:Process) WHERE toLower(proc.type) CONTAINS toLower($process) }");
            totalParams.put("process", process);
        }
        if (origin != null && !origin.isEmpty()) {
            // Support comma-separated origins (OR logic)
            List<String> originsList = Arrays.stream(origin.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.toList());
            if (originsList.size() == 1) {
                totalWhere.append(" AND EXISTS { (p)-[:FROM_ORIGIN]->(o:Origin) WHERE toLower(o.country) = $origin }");
                totalParams.put("origin", originsList.get(0));
            } else {
                totalWhere.append(" AND EXISTS { (p)-[:FROM_ORIGIN]->(o:Origin) WHERE toLower(o.country) IN $origins }");
                totalParams.put("origins", originsList);
            }
        }
        if (variety != null && !variety.isEmpty()) {
            totalWhere.append(" AND EXISTS { (p)-[:HAS_VARIETY]->(v:Variety) WHERE toLower(v.name) CONTAINS toLower($variety) }");
            totalParams.put("variety", variety);
        }
        if (roast != null && !roast.isEmpty()) {
            totalWhere.append(" AND EXISTS { (p)-[:ROASTED_AT]->(r:RoastLevel) WHERE r.level = $roast }");
            totalParams.put("roast", roast);
        }

        String totalCypher = String.format("""
            MATCH (p:Product)
            %s
            RETURN count(p) as total
            """, totalWhere.toString());

        Collection<Map<String, Object>> totalResult = executeQueryWithParams(totalCypher, totalParams);
        long total = 0;
        if (!totalResult.isEmpty()) {
            Object totalObj = totalResult.iterator().next().get("total");
            total = totalObj instanceof Long ? (Long) totalObj : ((Number) totalObj).longValue();
        }
        result.put("totalProducts", total);

        return ResponseEntity.ok(result);
    }

    /**
     * Get filtered products with all filter dimensions
     */
    @GetMapping("/filter-products")
    @Operation(summary = "Get filtered products",
               description = "Returns products matching all selected filters with pagination")
    public ResponseEntity<Map<String, Object>> getFilteredProducts(
            @RequestParam(required = false) String process,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String variety,
            @RequestParam(required = false) String roast,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("Getting filtered products - process={}, origin={}, variety={}, roast={}, page={}",
                 process, origin, variety, roast, page);

        // Build WHERE clause using relationships
        StringBuilder whereClause = new StringBuilder("WHERE (p.deleted IS NULL OR p.deleted = false)");
        Map<String, Object> params = new HashMap<>();

        if (process != null && !process.isEmpty()) {
            whereClause.append(" AND EXISTS { (p)-[:HAS_PROCESS]->(proc:Process) WHERE toLower(proc.type) CONTAINS toLower($process) }");
            params.put("process", process);
        }
        if (origin != null && !origin.isEmpty()) {
            // Support comma-separated origins (OR logic)
            List<String> origins = Arrays.stream(origin.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.toList());
            if (origins.size() == 1) {
                whereClause.append(" AND EXISTS { (p)-[:FROM_ORIGIN]->(o:Origin) WHERE toLower(o.country) = $origin }");
                params.put("origin", origins.get(0));
            } else {
                whereClause.append(" AND EXISTS { (p)-[:FROM_ORIGIN]->(o:Origin) WHERE toLower(o.country) IN $origins }");
                params.put("origins", origins);
            }
        }
        if (variety != null && !variety.isEmpty()) {
            whereClause.append(" AND EXISTS { (p)-[:HAS_VARIETY]->(v:Variety) WHERE toLower(v.name) CONTAINS toLower($variety) }");
            params.put("variety", variety);
        }
        if (roast != null && !roast.isEmpty()) {
            whereClause.append(" AND EXISTS { (p)-[:ROASTED_AT]->(r:RoastLevel) WHERE r.level = $roast }");
            params.put("roast", roast);
        }

        // Get total count
        String countCypher = String.format("""
            MATCH (p:Product)
            %s
            RETURN count(p) as total
            """, whereClause.toString());

        Collection<Map<String, Object>> countResult = executeQueryWithParams(countCypher, params);
        long totalCount = 0;
        if (!countResult.isEmpty()) {
            Object totalObj = countResult.iterator().next().get("total");
            totalCount = totalObj instanceof Long ? (Long) totalObj : ((Number) totalObj).longValue();
        }

        // Get paginated products with related data via relationships
        int skip = page * size;
        String productsCypher = String.format("""
            MATCH (p:Product)
            %s
            OPTIONAL MATCH (p)-[:SOLD_BY]->(b:Brand)
            OPTIONAL MATCH (p)-[:FROM_ORIGIN]->(o:Origin)
            OPTIONAL MATCH (p)-[:HAS_PROCESS]->(proc:Process)
            OPTIONAL MATCH (p)-[:HAS_VARIETY]->(v:Variety)
            OPTIONAL MATCH (p)-[:ROASTED_AT]->(r:RoastLevel)
            OPTIONAL MATCH (p)-[:HAS_TASTING_NOTE]->(tn:TastingNote)
            WITH p, b,
                 COLLECT(DISTINCT o.country) as origins,
                 COLLECT(DISTINCT proc.type) as processes,
                 COLLECT(DISTINCT v.name) as varieties,
                 COLLECT(DISTINCT r.level) as roasts,
                 COLLECT(DISTINCT tn.rawText)[0..5] as tastingNotes
            RETURN p.productId as id, p.productName as productName, b.name as brandName,
                   CASE WHEN size(origins) > 0 THEN
                       REDUCE(s = '', x IN origins | s + CASE WHEN s = '' THEN '' ELSE ' / ' END + x)
                   ELSE null END as origin,
                   CASE WHEN size(roasts) > 0 THEN roasts[0] ELSE null END as roastLevel,
                   CASE WHEN size(processes) > 0 THEN processes[0] ELSE null END as process,
                   CASE WHEN size(varieties) > 0 THEN
                       REDUCE(s = '', x IN varieties | s + CASE WHEN s = '' THEN '' ELSE ', ' END + x)
                   ELSE null END as variety,
                   p.price as price,
                   tastingNotes
            ORDER BY p.productName
            SKIP $skip LIMIT $size
            """, whereClause.toString());

        params.put("skip", skip);
        params.put("size", size);

        Collection<Map<String, Object>> productsResult = executeQueryWithParams(productsCypher, params);

        List<Map<String, Object>> products = new ArrayList<>();
        for (Map<String, Object> row : productsResult) {
            Map<String, Object> product = new HashMap<>();
            product.put("id", row.get("id"));
            product.put("productName", row.get("productName"));
            product.put("brandName", row.get("brandName"));
            product.put("origin", row.get("origin"));
            product.put("roastLevel", row.get("roastLevel"));
            product.put("process", row.get("process"));
            product.put("variety", row.get("variety"));
            product.put("price", row.get("price"));
            product.put("tastingNotes", row.get("tastingNotes"));
            products.add(product);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("products", products);
        result.put("totalCount", totalCount);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (int) Math.ceil((double) totalCount / size));

        return ResponseEntity.ok(result);
    }

    /**
     * Execute a Neo4j query with parameters
     */
    private Collection<Map<String, Object>> executeQueryWithParams(String cypher, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return neo4jClient.query(cypher).fetch().all();
        }

        // Build the query with all parameters bound
        var entries = params.entrySet().iterator();
        if (!entries.hasNext()) {
            return neo4jClient.query(cypher).fetch().all();
        }

        var first = entries.next();
        var boundQuery = neo4jClient.query(cypher).bind(first.getValue()).to(first.getKey());

        while (entries.hasNext()) {
            var entry = entries.next();
            boundQuery = boundQuery.bind(entry.getValue()).to(entry.getKey());
        }

        return boundQuery.fetch().all();
    }

    /**
     * Execute a count query and return list of name-count pairs
     */
    private List<Map<String, Object>> executeCountQuery(String cypher, Map<String, Object> params,
                                                         String nameField, String countField) {
        Collection<Map<String, Object>> results = executeQueryWithParams(cypher, params);

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> row : results) {
            String name = (String) row.get(nameField);
            Object countObj = row.get(countField);
            long count = countObj instanceof Long ? (Long) countObj : ((Number) countObj).longValue();
            if (name != null && !name.trim().isEmpty()) {
                Map<String, Object> item = new HashMap<>();
                item.put("name", name.trim());
                item.put("count", count);
                list.add(item);
            }
        }
        return list;
    }

    /**
     * Normalize process names - group similar processes together
     */
    private List<Map<String, Object>> normalizeProcessCounts(List<Map<String, Object>> raw) {
        Map<String, Long> grouped = new LinkedHashMap<>();

        for (Map<String, Object> item : raw) {
            String name = ((String) item.get("name")).toLowerCase().trim();
            long count = ((Number) item.get("count")).longValue();

            // Normalize process names
            String normalized;
            if (name.contains("washed") || name.contains("wet")) {
                normalized = "Washed";
            } else if (name.contains("natural") || name.contains("dry") || name.contains("sun-dried")) {
                normalized = "Natural";
            } else if (name.contains("honey") || name.contains("pulped")) {
                normalized = "Honey";
            } else if (name.contains("anaerobic")) {
                normalized = "Anaerobic";
            } else if (name.contains("carbonic") || name.contains("maceration")) {
                normalized = "Carbonic Maceration";
            } else {
                // Keep original but capitalize
                normalized = capitalizeWords((String) item.get("name"));
            }

            grouped.merge(normalized, count, Long::sum);
        }

        return grouped.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /**
     * Normalize origin names - extract primary country from blends
     */
    private List<Map<String, Object>> normalizeOriginCounts(List<Map<String, Object>> raw) {
        Map<String, Long> grouped = new LinkedHashMap<>();

        for (Map<String, Object> item : raw) {
            String name = (String) item.get("name");
            long count = ((Number) item.get("count")).longValue();

            // Handle blends like "Ethiopia / Colombia" - count for each country
            String[] parts = name.split("\\s*/\\s*|\\s*,\\s*|\\s+and\\s+");
            for (String part : parts) {
                String normalized = capitalizeWords(part.trim());
                if (!normalized.isEmpty()) {
                    grouped.merge(normalized, count, Long::sum);
                }
            }
        }

        return grouped.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /**
     * Normalize variety names - group similar varieties
     */
    private List<Map<String, Object>> normalizeVarietyCounts(List<Map<String, Object>> raw) {
        Map<String, Long> grouped = new LinkedHashMap<>();

        for (Map<String, Object> item : raw) {
            String name = (String) item.get("name");
            long count = ((Number) item.get("count")).longValue();

            // Handle multi-variety like "Bourbon, Caturra" - count for each
            String[] parts = name.split("\\s*/\\s*|\\s*,\\s*|\\s+&\\s+|\\s+and\\s+");
            for (String part : parts) {
                String normalized = capitalizeWords(part.trim());
                if (!normalized.isEmpty() && normalized.length() > 1) {
                    grouped.merge(normalized, count, Long::sum);
                }
            }
        }

        return grouped.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .limit(30) // Limit to top 30 varieties
                .collect(Collectors.toList());
    }

    /**
     * Capitalize first letter of each word
     */
    private String capitalizeWords(String str) {
        if (str == null || str.isEmpty()) return str;
        String[] words = str.toLowerCase().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) sb.append(word.substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * Map CoffeeProduct entity to response format
     */
    private Map<String, Object> mapProductToResponse(CoffeeProduct product) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", product.getId());
        map.put("productName", product.getProductName());
        map.put("brandName", product.getBrand() != null ? product.getBrand().getName() : null);
        map.put("origin", product.getOrigin());
        map.put("region", product.getRegion());
        map.put("roastLevel", product.getRoastLevel());
        map.put("process", product.getProcess());
        map.put("variety", product.getVariety());
        map.put("price", product.getPrice());
        map.put("tastingNotes", product.getTastingNotesJson());
        return map;
    }
}
