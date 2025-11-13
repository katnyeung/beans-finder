package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.graph.node.ProductNode;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.coffee.beansfinder.service.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for querying the knowledge graph
 */
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeGraphController {

    private final KnowledgeGraphService graphService;
    private final CoffeeProductRepository productRepository;
    private final CoffeeBrandRepository brandRepository;

    /**
     * Find products by flavor
     */
    @GetMapping("/products/flavor/{flavorName}")
    public List<ProductNode> findByFlavor(@PathVariable String flavorName) {
        return graphService.findProductsByFlavor(flavorName);
    }

    /**
     * Find products by SCA category
     */
    @GetMapping("/products/sca-category/{categoryName}")
    public List<ProductNode> findBySCACategory(@PathVariable String categoryName) {
        return graphService.findProductsBySCACategory(categoryName);
    }

    /**
     * Find products by origin
     */
    @GetMapping("/products/origin/{country}")
    public List<ProductNode> findByOrigin(@PathVariable String country) {
        return graphService.findProductsByOrigin(country);
    }

    /**
     * Find products by process
     */
    @GetMapping("/products/process/{processType}")
    public List<ProductNode> findByProcess(@PathVariable String processType) {
        return graphService.findProductsByProcess(processType);
    }

    /**
     * Complex query: Find products by process AND flavor
     * Example: honey-processed Geishas with pear-like sweetness
     */
    @GetMapping("/products/complex")
    public List<ProductNode> findByProcessAndFlavor(
            @RequestParam String process,
            @RequestParam String flavor) {
        return graphService.findProductsByProcessAndFlavor(process, flavor);
    }

    /**
     * Initialize SCA categories in graph
     */
    @PostMapping("/init-categories")
    public String initializeCategories() {
        graphService.initializeSCACategories();
        return "SCA categories initialized";
    }

    /**
     * SINGLE COMPREHENSIVE CLEANUP & REBUILD ENDPOINT
     *
     * This endpoint wipes and rebuilds the entire Neo4j knowledge graph:
     * 1. Deletes all ProductNodes
     * 2. Re-syncs ALL products from PostgreSQL with clean logic (no "Unknown" nodes)
     * 3. Automatically cleans up orphaned nodes
     * 4. Preserves Flavor and SCACategory nodes
     *
     * Safe to run multiple times. This is the ONLY cleanup endpoint you need.
     */
    @PostMapping("/cleanup-and-rebuild")
    public ResponseEntity<Map<String, Object>> cleanupAndRebuild() {
        log.info("Starting cleanup and rebuild of Neo4j knowledge graph");

        try {
            Map<String, Object> result = graphService.cleanupAndRebuild();
            log.info("Cleanup and rebuild completed successfully");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Cleanup and rebuild failed: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Re-sync products for a specific brand to Neo4j.
     * Useful for testing or fixing specific brands.
     */
    @PostMapping("/re-sync-brand/{brandId}")
    public ResponseEntity<Map<String, Object>> reSyncBrandProducts(@PathVariable Long brandId) {
        log.info("Starting re-sync of products for brand ID: {}", brandId);
        long startTime = System.currentTimeMillis();

        try {
            // Get brand
            CoffeeBrand brand = brandRepository.findById(brandId)
                    .orElseThrow(() -> new RuntimeException("Brand not found: " + brandId));

            // Get all products for this brand
            List<CoffeeProduct> products = productRepository.findByBrandId(brandId);
            log.info("Found {} products for brand: {}", products.size(), brand.getName());

            int successCount = 0;
            int errorCount = 0;
            StringBuilder errors = new StringBuilder();

            // Re-sync each product
            for (CoffeeProduct product : products) {
                try {
                    graphService.reSyncProduct(product);
                    successCount++;
                } catch (Exception e) {
                    errorCount++;
                    log.error("Failed to re-sync product {}: {}", product.getId(), e.getMessage());
                    errors.append(String.format("Product %d: %s; ", product.getId(), e.getMessage()));
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Re-sync completed for brand {}: {} success, {} errors in {}ms",
                    brand.getName(), successCount, errorCount, duration);

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("brandId", brandId);
            response.put("brandName", brand.getName());
            response.put("totalProducts", products.size());
            response.put("successCount", successCount);
            response.put("errorCount", errorCount);
            response.put("durationMs", duration);

            if (errorCount > 0) {
                response.put("errors", errors.toString());
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Re-sync failed for brand {}: {}", brandId, e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Find products by brand name (via SOLD_BY relationship)
     */
    @GetMapping("/products/brand/{brandName}")
    public ResponseEntity<List<ProductNode>> findByBrand(@PathVariable String brandName) {
        List<ProductNode> products = graphService.findProductsByBrandName(brandName);
        return ResponseEntity.ok(products);
    }

    /**
     * Find products by roast level
     */
    @GetMapping("/products/roast-level/{level}")
    public ResponseEntity<List<ProductNode>> findByRoastLevel(@PathVariable String level) {
        List<ProductNode> products = graphService.findProductsByRoastLevel(level);
        return ResponseEntity.ok(products);
    }

    /**
     * Delete all TastingNote nodes (cleanup after removing TastingNoteNode)
     */
    @PostMapping("/delete-tasting-note-nodes")
    public ResponseEntity<Map<String, Object>> deleteTastingNoteNodes() {
        log.info("Deleting all TastingNote nodes from Neo4j");

        try {
            long deletedCount = graphService.deleteTastingNoteNodes();

            Map<String, Object> response = new HashMap<>();
            response.put("deletedCount", deletedCount);
            response.put("message", "Successfully deleted " + deletedCount + " TastingNote nodes");

            log.info("Deleted {} TastingNote nodes", deletedCount);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to delete TastingNote nodes: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Migrate brands from ProductNode property to BrandNode relationship
     * Also creates FlavorNodes from tasting_notes_json (lowercase normalized)
     */
    @PostMapping("/migrate-brands-to-nodes")
    public ResponseEntity<Map<String, Object>> migrateBrandsToNodes() {
        log.info("Starting brand migration and flavor normalization");
        long startTime = System.currentTimeMillis();

        try {
            // This will:
            // 1. Create BrandNode for each brand
            // 2. Create FlavorNode from both sca_flavors_json AND tasting_notes_json (lowercase)
            // 3. Link products to brands via SOLD_BY
            // 4. Link products to flavors via HAS_FLAVOR
            Map<String, Object> result = graphService.cleanupAndRebuild();

            long duration = System.currentTimeMillis() - startTime;
            result.put("migrationDurationMs", duration);
            result.put("message", "Migration completed. BrandNodes created, FlavorNodes normalized to lowercase.");

            log.info("Migration completed successfully in {}ms", duration);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Migration failed: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get Neo4j graph statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getGraphStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("productCount", graphService.getProductCount());
        return ResponseEntity.ok(stats);
    }

    /**
     * Fix null SCA categories - set all null categories to 'other'
     */
    @PostMapping("/fix-null-categories")
    public ResponseEntity<Map<String, Object>> fixNullCategories() {
        try {
            int fixed = graphService.fixNullScaCategories();

            Map<String, Object> response = new HashMap<>();
            response.put("flavorsFixed", fixed);
            response.put("message", "Fixed " + fixed + " flavors with null SCA categories");

            log.info("Fixed {} flavor nodes with null SCA categories", fixed);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to fix null categories: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Delete invalid origin nodes (blends, multi-origins, malformed data)
     * Examples: "Blend (Colombia", "Brazil)", "Huila, Minas Gerais, Sidamo"
     */
    @PostMapping("/cleanup-invalid-origins")
    public ResponseEntity<Map<String, Object>> cleanupInvalidOrigins() {
        try {
            log.info("Starting cleanup of invalid origin nodes...");

            int deletedCount = graphService.deleteInvalidOriginNodes();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("deletedCount", deletedCount);
            response.put("message", "Successfully deleted " + deletedCount + " invalid origin nodes");

            log.info("Deleted {} invalid origin nodes", deletedCount);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to cleanup invalid origins: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Cleanup and merge duplicate flavor nodes (case-insensitive)
     * Example: "smooth" (23) + "Smooth" (9) → "smooth" (32)
     * Also marks non-flavor descriptors to stay in 'other' category
     * Should be run BEFORE re-analysis
     */
    @PostMapping("/cleanup-merge-flavors")
    public ResponseEntity<Map<String, Object>> cleanupAndMergeFlavors() {
        try {
            log.info("Starting flavor cleanup and merge...");

            Map<String, Object> result = graphService.cleanupAndMergeFlavors();

            log.info("Cleanup complete: {}", result.get("message"));
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Failed to cleanup flavors: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            errorResponse.put("message", "Cleanup failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Re-analyze 'other' category flavors using OpenAI to properly categorize them
     * Processes remaining flavors in batches of 50
     * Cost: ~$0.02 total
     * Time: ~1-2 minutes
     *
     * IMPORTANT: Run /cleanup-merge-flavors first for best results!
     */
    @PostMapping("/re-analyze-other-flavors")
    public ResponseEntity<Map<String, Object>> reAnalyzeOtherFlavors() {
        try {
            log.info("Starting OpenAI re-analysis of 'other' category flavors...");

            Map<String, Object> result = graphService.reAnalyzeOtherFlavorsWithOpenAI();

            log.info("Re-analysis complete: {}", result.get("message"));
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Failed to re-analyze flavors: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            errorResponse.put("message", "Re-analysis failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}
