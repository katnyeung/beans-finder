package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.dto.SCAFlavorMapping;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.coffee.beansfinder.service.CostTrackingService;
import com.coffee.beansfinder.service.CrawlerService;
import com.coffee.beansfinder.service.KnowledgeGraphService;
import com.coffee.beansfinder.service.RateLimiterService;
import com.coffee.beansfinder.service.SemanticCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin controller for monitoring and managing chatbot system
 * Provides endpoints for cost tracking, rate limiting status, and cache management
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Admin endpoints for monitoring and management")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final CostTrackingService costTrackingService;
    private final RateLimiterService rateLimiterService;
    private final SemanticCacheService semanticCacheService;
    private final RedisTemplate<String, String> redisTemplate;
    private final CoffeeProductRepository productRepository;
    private final CrawlerService crawlerService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final ObjectMapper objectMapper;

    /**
     * Get today's cost statistics
     */
    @GetMapping("/cost/today")
    @Operation(summary = "Get today's API cost statistics",
            description = "Returns current cost, limit, query count, and remaining budget")
    public ResponseEntity<CostTrackingService.CostStats> getTodayCost() {
        return ResponseEntity.ok(costTrackingService.getStats());
    }

    /**
     * Get rate limiting status
     */
    @GetMapping("/ratelimit/status")
    @Operation(summary = "Get rate limiting status",
            description = "Returns number of active IPs being rate limited")
    public ResponseEntity<Map<String, Object>> getRateLimitStatus() {
        // Count active rate limit keys
        Set<String> minuteKeys = redisTemplate.keys("ratelimit:minute:*");
        Set<String> dailyKeys = redisTemplate.keys("ratelimit:daily:*");

        Map<String, Object> status = new HashMap<>();
        status.put("activeIPsLastMinute", minuteKeys != null ? minuteKeys.size() : 0);
        status.put("activeIPsToday", dailyKeys != null ? dailyKeys.size() : 0);
        status.put("limits", Map.of(
            "perMinute", 10,
            "perDay", 200
        ));

        return ResponseEntity.ok(status);
    }

    /**
     * Get rate limit status for specific IP
     */
    @GetMapping("/ratelimit/ip/{ip}")
    @Operation(summary = "Get rate limit status for specific IP")
    public ResponseEntity<RateLimiterService.RateLimitStatus> getIpStatus(@PathVariable String ip) {
        return ResponseEntity.ok(rateLimiterService.getStatus(ip));
    }

    /**
     * Reset daily cost (admin emergency function)
     */
    @PostMapping("/cost/reset")
    @Operation(summary = "Reset daily cost counter",
            description = "Emergency function to reset daily cost budget. Use with caution!")
    public ResponseEntity<Map<String, String>> resetDailyCost() {
        costTrackingService.resetDailyCost();
        log.warn("ADMIN ACTION: Daily cost reset");
        return ResponseEntity.ok(Map.of(
            "message", "Daily cost reset successfully",
            "warning", "This resets the cost budget protection. Monitor costs carefully."
        ));
    }

    /**
     * Clear all rate limit keys (admin emergency function)
     */
    @PostMapping("/ratelimit/reset")
    @Operation(summary = "Clear all rate limit counters",
            description = "Emergency function to clear all rate limit restrictions")
    public ResponseEntity<Map<String, Object>> resetRateLimits() {
        Set<String> minuteKeys = redisTemplate.keys("ratelimit:minute:*");
        Set<String> dailyKeys = redisTemplate.keys("ratelimit:daily:*");

        int deletedCount = 0;
        if (minuteKeys != null) {
            deletedCount += minuteKeys.size();
            redisTemplate.delete(minuteKeys);
        }
        if (dailyKeys != null) {
            deletedCount += dailyKeys.size();
            redisTemplate.delete(dailyKeys);
        }

        log.warn("ADMIN ACTION: Rate limits reset ({} keys deleted)", deletedCount);
        return ResponseEntity.ok(Map.of(
            "message", "Rate limits reset successfully",
            "keysDeleted", deletedCount
        ));
    }

    /**
     * Get semantic cache statistics
     */
    @GetMapping("/cache/semantic/stats")
    @Operation(summary = "Get semantic cache statistics",
            description = "Returns cache size, hit rate, similarity threshold")
    public ResponseEntity<SemanticCacheService.CacheStats> getSemanticCacheStats() {
        return ResponseEntity.ok(semanticCacheService.getStats());
    }

    /**
     * Clear semantic cache
     */
    @PostMapping("/cache/semantic/clear")
    @Operation(summary = "Clear all semantic cache entries",
            description = "Removes all cached chatbot responses and embeddings")
    public ResponseEntity<Map<String, Object>> clearSemanticCache() {
        semanticCacheService.clearCache();
        log.warn("ADMIN ACTION: Semantic cache cleared");
        return ResponseEntity.ok(Map.of(
            "message", "Semantic cache cleared successfully",
            "warning", "New queries will require full Grok processing until cache rebuilds"
        ));
    }

    /**
     * Get system health summary
     */
    @GetMapping("/health")
    @Operation(summary = "Get system health summary")
    public ResponseEntity<Map<String, Object>> getHealth() {
        CostTrackingService.CostStats costStats = costTrackingService.getStats();
        SemanticCacheService.CacheStats cacheStats = semanticCacheService.getStats();

        Map<String, Object> health = new HashMap<>();
        health.put("status", "healthy");
        health.put("cost", Map.of(
            "current", costStats.currentCost(),
            "limit", costStats.dailyLimit(),
            "remaining", costStats.remainingBudget(),
            "utilizationPercent", (costStats.currentCost() / costStats.dailyLimit()) * 100
        ));
        health.put("queries", Map.of(
            "today", costStats.queryCount(),
            "remaining", costStats.remainingQueries()
        ));
        health.put("semanticCache", Map.of(
            "cachedQueries", cacheStats.cachedQueries(),
            "hitRate", cacheStats.hitRate(),
            "hits", cacheStats.cacheHits(),
            "misses", cacheStats.cacheMisses()
        ));

        return ResponseEntity.ok(health);
    }

    // ===== Product Update Request Management =====

    /**
     * Get list of products flagged for update
     */
    @GetMapping("/products/update-requested")
    @Operation(summary = "Get products flagged for update",
            description = "Returns list of products that users have requested to be refreshed")
    public ResponseEntity<Map<String, Object>> getUpdateRequestedProducts() {
        List<CoffeeProduct> flaggedProducts = productRepository.findByUpdateRequestedTrue();

        List<Map<String, Object>> productList = new ArrayList<>();
        for (CoffeeProduct p : flaggedProducts) {
            Map<String, Object> productInfo = new HashMap<>();
            productInfo.put("id", p.getId());
            productInfo.put("productName", p.getProductName());
            productInfo.put("brandId", p.getBrand().getId());
            productInfo.put("brandName", p.getBrand().getName());
            productInfo.put("sellerUrl", p.getSellerUrl());
            productInfo.put("lastUpdateDate", p.getLastUpdateDate());
            productList.add(productInfo);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("count", flaggedProducts.size());
        response.put("products", productList);

        return ResponseEntity.ok(response);
    }

    /**
     * Count products flagged for update
     */
    @GetMapping("/products/update-requested/count")
    @Operation(summary = "Count products flagged for update")
    public ResponseEntity<Map<String, Long>> countUpdateRequestedProducts() {
        long count = productRepository.countByUpdateRequestedTrue();
        return ResponseEntity.ok(Map.of("count", count));
    }

    /**
     * Refresh all flagged products (re-crawl them)
     */
    @PostMapping("/products/refresh-flagged")
    @Operation(summary = "Refresh all flagged products",
            description = "Re-crawl all products that have been flagged for update")
    public ResponseEntity<Map<String, Object>> refreshFlaggedProducts() {
        List<CoffeeProduct> flaggedProducts = productRepository.findByUpdateRequestedTrue();

        if (flaggedProducts.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "message", "No products flagged for update",
                "refreshed", 0,
                "failed", 0
            ));
        }

        log.info("Starting refresh of {} flagged products", flaggedProducts.size());

        int refreshed = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (CoffeeProduct product : flaggedProducts) {
            try {
                if (product.getSellerUrl() == null || product.getSellerUrl().isEmpty()) {
                    log.warn("Skipping product {} - no seller URL", product.getId());
                    failed++;
                    errors.add("Product " + product.getId() + ": No seller URL");
                    continue;
                }

                log.info("Re-crawling product {} (ID: {})", product.getProductName(), product.getId());

                CoffeeProduct updatedProduct = crawlerService.crawlProduct(
                        product.getBrand(),
                        product.getSellerUrl(),
                        product.getId()
                );

                if (updatedProduct != null) {
                    // Clear the flag
                    updatedProduct.setUpdateRequested(false);
                    productRepository.save(updatedProduct);
                    refreshed++;
                } else {
                    failed++;
                    errors.add("Product " + product.getId() + ": Crawl returned null");
                }
            } catch (Exception e) {
                log.error("Failed to refresh product {}: {}", product.getId(), e.getMessage());
                failed++;
                errors.add("Product " + product.getId() + ": " + e.getMessage());
            }
        }

        log.info("Refresh completed: {} refreshed, {} failed", refreshed, failed);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Refresh completed");
        response.put("total", flaggedProducts.size());
        response.put("refreshed", refreshed);
        response.put("failed", failed);
        if (!errors.isEmpty()) {
            response.put("errors", errors);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Refresh a single flagged product by ID
     */
    @PostMapping("/products/{id}/refresh")
    @Operation(summary = "Refresh a single product",
            description = "Re-crawl a specific product and clear its update flag")
    public ResponseEntity<Map<String, Object>> refreshProduct(@PathVariable Long id) {
        try {
            CoffeeProduct product = productRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));

            if (product.getSellerUrl() == null || product.getSellerUrl().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Product has no seller URL to crawl"
                ));
            }

            log.info("Admin refresh: Re-crawling product {} (ID: {})", product.getProductName(), id);

            CoffeeProduct updatedProduct = crawlerService.crawlProduct(
                    product.getBrand(),
                    product.getSellerUrl(),
                    id
            );

            if (updatedProduct != null) {
                // Clear the flag
                updatedProduct.setUpdateRequested(false);
                productRepository.save(updatedProduct);

                return ResponseEntity.ok(Map.of(
                    "message", "Product refreshed successfully",
                    "productId", id,
                    "productName", updatedProduct.getProductName()
                ));
            } else {
                return ResponseEntity.status(500).body(Map.of(
                    "error", "Crawl returned null"
                ));
            }
        } catch (Exception e) {
            log.error("Failed to refresh product {}: {}", id, e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Clear update flag for a product without re-crawling
     */
    @PostMapping("/products/{id}/clear-flag")
    @Operation(summary = "Clear update flag without re-crawling",
            description = "Dismiss the update request without actually refreshing the product")
    public ResponseEntity<Map<String, String>> clearUpdateFlag(@PathVariable Long id) {
        try {
            CoffeeProduct product = productRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));

            product.setUpdateRequested(false);
            productRepository.save(product);

            log.info("Cleared update flag for product {} (ID: {})", product.getProductName(), id);

            return ResponseEntity.ok(Map.of(
                "message", "Update flag cleared",
                "productId", String.valueOf(id)
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "error", e.getMessage()
            ));
        }
    }

    // ===== Flavor Profile Backfill =====

    /**
     * Backfill flavor profiles for existing products.
     * Generates 9-dimensional flavor profile from existing SCA flavor mapping.
     * Uses count-based intensity: 0 notes = 0.0, 1 note = 0.4, 2 notes = 0.6, 3+ notes = 0.8
     * Also sets neutral character axes [0.0, 0.0, 0.0, 0.0] for products without profiles.
     *
     * @param rebuildGraph If true, rebuilds Neo4j knowledge graph after backfill
     */
    @PostMapping("/backfill-flavor-profiles")
    @Operation(summary = "Backfill flavor profiles for existing products",
            description = "Generates flavor profiles from SCA mapping for products missing profiles. Set rebuildGraph=true to sync to Neo4j.")
    public ResponseEntity<Map<String, Object>> backfillFlavorProfiles(
            @RequestParam(defaultValue = "false") boolean rebuildGraph) {

        log.info("Starting flavor profile backfill (rebuildGraph={})", rebuildGraph);

        List<CoffeeProduct> products = productRepository.findAll();

        int updated = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (CoffeeProduct product : products) {
            try {
                boolean needsUpdate = false;

                // Check if flavor profile is missing or empty
                if (product.getFlavorProfileJson() == null || product.getFlavorProfileJson().isEmpty()
                        || product.getFlavorProfileJson().equals("[]")) {

                    // Generate from SCA mapping
                    if (product.getScaFlavorsJson() != null && !product.getScaFlavorsJson().isEmpty()) {
                        SCAFlavorMapping mapping = objectMapper.readValue(
                                product.getScaFlavorsJson(),
                                SCAFlavorMapping.class
                        );
                        List<Double> profile = crawlerService.generateFlavorProfileFromSCA(mapping);
                        product.setFlavorProfileJson(objectMapper.writeValueAsString(profile));
                        needsUpdate = true;
                    } else {
                        // No SCA mapping, set all zeros
                        product.setFlavorProfileJson("[0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]");
                        needsUpdate = true;
                    }
                }

                // Check if character axes is missing or empty
                if (product.getCharacterAxesJson() == null || product.getCharacterAxesJson().isEmpty()
                        || product.getCharacterAxesJson().equals("[]")) {
                    // Infer character axes from product attributes
                    List<Double> axes = inferCharacterAxes(product);
                    product.setCharacterAxesJson(objectMapper.writeValueAsString(axes));
                    needsUpdate = true;
                }

                if (needsUpdate) {
                    productRepository.save(product);
                    updated++;
                } else {
                    skipped++;
                }

            } catch (Exception e) {
                failed++;
                errors.add("Product " + product.getId() + ": " + e.getMessage());
                log.error("Failed to backfill profile for product {}: {}", product.getId(), e.getMessage());
            }
        }

        log.info("Flavor profile backfill complete: {} updated, {} skipped, {} failed", updated, skipped, failed);

        // Optionally rebuild Neo4j
        if (rebuildGraph && updated > 0) {
            log.info("Rebuilding Neo4j knowledge graph...");
            try {
                knowledgeGraphService.cleanupAndRebuild();
                log.info("Neo4j rebuild complete");
            } catch (Exception e) {
                log.error("Failed to rebuild Neo4j: {}", e.getMessage());
                errors.add("Neo4j rebuild failed: " + e.getMessage());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Flavor profile backfill complete");
        response.put("total", products.size());
        response.put("updated", updated);
        response.put("skipped", skipped);
        response.put("failed", failed);
        response.put("graphRebuilt", rebuildGraph && updated > 0);
        if (!errors.isEmpty()) {
            response.put("errors", errors);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Infer character axes from product attributes (origin, process, roast level).
     * Uses the same inference rules as defined in OpenAI prompt.
     * @return 4-dimensional character axes: [acidity, body, roast, complexity]
     */
    private List<Double> inferCharacterAxes(CoffeeProduct product) {
        double acidity = 0.0;
        double body = 0.0;
        double roast = 0.0;
        double complexity = 0.0;

        // Origin-based inference
        String origin = product.getOrigin();
        if (origin != null) {
            origin = origin.toLowerCase();
            // Bright acidity origins
            if (origin.contains("ethiopia") || origin.contains("kenya")) {
                acidity += 0.4;
                complexity += 0.2;
            }
            // Full body origins
            if (origin.contains("brazil") || origin.contains("sumatra") || origin.contains("indonesia")) {
                body += 0.3;
                acidity -= 0.2;
            }
            // Clean profile origins
            if (origin.contains("colombia") || origin.contains("guatemala")) {
                acidity += 0.1;
            }
        }

        // Process-based inference
        String process = product.getProcess();
        if (process != null) {
            process = process.toLowerCase();
            if (process.contains("natural") || process.contains("dry")) {
                body += 0.3;
                complexity += 0.3;
            }
            if (process.contains("washed") || process.contains("wet")) {
                acidity += 0.2;
                complexity -= 0.1;
            }
            if (process.contains("honey") || process.contains("pulped natural")) {
                body += 0.2;
                complexity += 0.1;
            }
            if (process.contains("anaerobic") || process.contains("fermented")) {
                complexity += 0.4;
            }
        }

        // Roast level-based inference
        String roastLevel = product.getRoastLevel();
        if (roastLevel != null) {
            roastLevel = roastLevel.toLowerCase();
            if (roastLevel.contains("light")) {
                roast = -0.5;
                acidity += 0.2;
            } else if (roastLevel.contains("medium-light") || roastLevel.contains("medium light")) {
                roast = -0.25;
                acidity += 0.1;
            } else if (roastLevel.contains("medium-dark") || roastLevel.contains("medium dark")) {
                roast = 0.25;
                body += 0.1;
            } else if (roastLevel.contains("dark")) {
                roast = 0.5;
                body += 0.2;
                acidity -= 0.2;
            } else if (roastLevel.contains("medium")) {
                roast = 0.0;
            }
        }

        // Clamp values to [-1.0, 1.0]
        acidity = Math.max(-1.0, Math.min(1.0, acidity));
        body = Math.max(-1.0, Math.min(1.0, body));
        roast = Math.max(-1.0, Math.min(1.0, roast));
        complexity = Math.max(-1.0, Math.min(1.0, complexity));

        return List.of(acidity, body, roast, complexity);
    }
}
