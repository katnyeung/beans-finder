package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.entity.PriceHistory;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.coffee.beansfinder.repository.UserProductTrackingRepository;
import com.coffee.beansfinder.service.CrawlerService;
import com.coffee.beansfinder.service.PriceHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * REST API for managing coffee products
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Products", description = "Coffee product management and queries")
public class ProductController {

    private final CoffeeProductRepository productRepository;
    private final CoffeeBrandRepository brandRepository;
    private final CrawlerService crawlerService;
    private final com.coffee.beansfinder.service.KnowledgeGraphService knowledgeGraphService;
    private final PriceHistoryService priceHistoryService;
    private final UserProductTrackingRepository trackingRepository;

    /**
     * Get all active (non-deleted) products
     */
    @GetMapping
    public List<CoffeeProduct> getAllProducts() {
        return productRepository.findByDeletedAtIsNull();
    }

    /**
     * Get new products (created in the last N days)
     */
    @Operation(
        summary = "Get new products",
        description = "Returns products that were first discovered/added within the specified number of days"
    )
    @GetMapping("/new")
    public List<NewProductDTO> getNewProducts(
            @Parameter(description = "Number of days to look back (default: 7)")
            @RequestParam(defaultValue = "7") int days) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        log.info("Fetching products created after: {}", cutoff);
        List<CoffeeProduct> products = productRepository.findByCreatedDateAfterOrderByCreatedDateDesc(cutoff);

        return products.stream()
                .filter(p -> p.getBrand() != null)
                .map(p -> new NewProductDTO(
                        p.getId(),
                        p.getProductName(),
                        p.getBrand().getId(),
                        p.getBrand().getName(),
                        p.getOrigin(),
                        p.getRoastLevel(),
                        p.getPrice(),
                        p.getPriceVariantsJson(),
                        p.getCurrency(),
                        p.getCreatedDate()
                ))
                .toList();
    }

    /**
     * Get recently updated products (updated in the last N days)
     */
    @Operation(
        summary = "Get recently updated products",
        description = "Returns products that were updated within the specified number of days"
    )
    @GetMapping("/updated")
    public List<CoffeeProduct> getRecentlyUpdatedProducts(
            @Parameter(description = "Number of days to look back (default: 7)")
            @RequestParam(defaultValue = "7") int days) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        log.info("Fetching products updated after: {}", cutoff);
        return productRepository.findByLastUpdateDateAfterOrderByLastUpdateDateDesc(cutoff);
    }

    /**
     * Get product by ID with brand information
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProductDetailDTO> getProductById(@PathVariable Long id) {
        return productRepository.findById(id)
                .map(product -> {
                    ProductDetailDTO dto = new ProductDetailDTO(
                            product.getId(),
                            product.getProductName(),
                            product.getBrand().getId(),
                            product.getBrand().getName(),
                            product.getOrigin(),
                            product.getRegion(),
                            product.getProcess(),
                            product.getProducer(),
                            product.getVariety(),
                            product.getAltitude(),
                            product.getRoastLevel(),
                            product.getTastingNotesJson(),
                            product.getScaFlavorsJson(),
                            product.getSellerUrl(),
                            product.getPrice(),
                            product.getPriceVariantsJson(),
                            product.getCurrency(),
                            product.getInStock(),
                            product.getRawDescription(),
                            product.getDescriptionSummary(),
                            product.getFlavorProfileJson(),
                            product.getCharacterAxesJson()
                    );
                    return ResponseEntity.ok(dto);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get products by brand (only valid coffee products with tasting notes)
     */
    @GetMapping("/brand/{brandId}")
    public List<CoffeeProduct> getProductsByBrand(@PathVariable Long brandId) {
        return productRepository.findValidProductsByBrandId(brandId);
    }

    /**
     * Get active products by origin
     */
    @GetMapping("/origin/{origin}")
    public List<CoffeeProduct> getProductsByOrigin(@PathVariable String origin) {
        return productRepository.findByOriginIgnoreCaseAndDeletedAtIsNull(origin);
    }

    /**
     * Search products by name or brand
     */
    @Operation(
        summary = "Search products by name or brand",
        description = "Search products by product name OR brand name (case-insensitive partial match)"
    )
    @GetMapping("/search")
    public List<ProductSearchResultDTO> searchProducts(
            @Parameter(description = "Search query (matches product name or brand name)") @RequestParam String query,
            @Parameter(description = "Maximum results to return") @RequestParam(defaultValue = "20") int limit) {

        if (query == null || query.trim().length() < 2) {
            return List.of();
        }

        log.info("Searching products for: '{}' (limit: {})", query, limit);

        List<CoffeeProduct> products = productRepository.searchActiveByProductOrBrandName(query.trim());

        return products.stream()
                .filter(p -> p.getBrand() != null)
                .limit(limit)
                .map(product -> new ProductSearchResultDTO(
                        product.getId(),
                        product.getProductName(),
                        product.getBrand().getName(),
                        product.getBrand().getId(),
                        product.getOrigin(),
                        product.getRoastLevel(),
                        product.getPrice(),
                        product.getPriceVariantsJson(),
                        product.getCurrency(),
                        product.getInStock()
                ))
                .toList();
    }

    /**
     * Get products by origin (region or country) with brand information (for map display).
     * Search order:
     * 1. Exact region match (case-insensitive)
     * 2. Partial region match (case-insensitive, for "Lintong, Sumatra" matching "Lintong")
     * 3. Exact country match (case-insensitive)
     */
    @GetMapping("/origin/{origin}/with-brand")
    public List<ProductWithBrandDTO> getProductsByOriginWithBrand(@PathVariable String origin) {
        List<CoffeeProduct> products;

        // 1. Try exact region match first (case-insensitive)
        products = productRepository.findByRegionIgnoreCase(origin);

        // 2. If no results, try partial region match (for "Lintong, Sumatra" matching "Lintong")
        if (products.isEmpty()) {
            products = productRepository.findByRegionContainingIgnoreCase(origin);
        }

        // 3. If still no results, try country match (case-insensitive)
        if (products.isEmpty()) {
            products = productRepository.findByOriginIgnoreCase(origin);
        }

        return products.stream()
                .filter(product -> product.getBrand() != null) // Safety check
                .map(product -> new ProductWithBrandDTO(
                        product.getId(),
                        product.getProductName(),
                        product.getBrand().getName(),
                        product.getBrand().getId(),
                        product.getOrigin(),
                        product.getRegion(),
                        product.getProcess(),
                        product.getProducer(),
                        product.getVariety(),
                        product.getAltitude(),
                        product.getSellerUrl(),
                        product.getPrice(),
                        product.getCurrency(),
                        product.getInStock()
                ))
                .toList();
    }

    /**
     * Get products by process
     */
    @GetMapping("/process/{process}")
    public List<CoffeeProduct> getProductsByProcess(@PathVariable String process) {
        return productRepository.findByProcess(process);
    }

    /**
     * Get products by variety
     */
    @GetMapping("/variety/{variety}")
    public List<CoffeeProduct> getProductsByVariety(@PathVariable String variety) {
        return productRepository.findByVariety(variety);
    }

    /**
     * Get in-stock products
     */
    @GetMapping("/in-stock")
    public List<CoffeeProduct> getInStockProducts() {
        return productRepository.findByInStockTrue();
    }

    /**
     * Get related products from the same brand
     */
    @GetMapping("/{id}/related")
    @Operation(summary = "Get related products", description = "Returns products from the same brand, excluding the current product")
    public ResponseEntity<List<CoffeeProduct>> getRelatedProducts(
            @PathVariable Long id,
            @Parameter(description = "Maximum number of related products to return") @RequestParam(defaultValue = "6") int limit) {
        return productRepository.findById(id)
                .map(product -> {
                    List<CoffeeProduct> related = productRepository.findByBrandId(product.getBrand().getId())
                            .stream()
                            .filter(p -> !p.getId().equals(id))
                            .limit(limit)
                            .toList();
                    return ResponseEntity.ok(related);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Sync roast levels from Neo4j to PostgreSQL for all products
     */
    @PostMapping("/sync-roast-levels")
    @Operation(summary = "Sync roast levels", description = "Backfill roast level data from Neo4j to PostgreSQL for all products")
    public ResponseEntity<String> syncRoastLevels() {
        try {
            List<CoffeeProduct> products = productRepository.findAll();
            int synced = 0;

            for (CoffeeProduct product : products) {
                try {
                    knowledgeGraphService.syncProductToGraph(product);
                    synced++;
                } catch (Exception e) {
                    log.error("Failed to sync product {}: {}", product.getId(), e.getMessage());
                }
            }

            return ResponseEntity.ok(String.format("Synced roast levels for %d/%d products", synced, products.size()));
        } catch (Exception e) {
            log.error("Failed to sync roast levels: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    /**
     * Request update for a specific product (flags for admin re-crawl)
     */
    @PostMapping("/{id}/request-update")
    @Operation(summary = "Request product update", description = "Flag product for admin to re-crawl later")
    public ResponseEntity<String> requestProductUpdate(@PathVariable Long id) {
        try {
            // Find the product
            CoffeeProduct product = productRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found"));

            // Check if product has a seller URL
            if (product.getSellerUrl() == null || product.getSellerUrl().isEmpty()) {
                return ResponseEntity.badRequest().body("Product has no seller URL to crawl");
            }

            // Flag the product for update
            product.setUpdateRequested(true);
            productRepository.save(product);

            log.info("Flagged product for update: {} (ID: {})", product.getProductName(), id);

            return ResponseEntity.ok("Update request submitted. An admin will review and refresh this product.");
        } catch (Exception e) {
            log.error("Failed to flag product {}: {}", id, e.getMessage());
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    /**
     * Manually crawl a specific product
     */
    @PostMapping("/crawl")
    public ResponseEntity<CoffeeProduct> crawlProduct(@RequestBody CrawlProductRequest request) {
        try {
            CoffeeBrand brand = brandRepository.findById(request.brandId)
                    .orElseThrow(() -> new IllegalArgumentException("Brand not found"));

            CoffeeProduct product = crawlerService.crawlProduct(brand, request.productUrl);

            if (product != null) {
                return ResponseEntity.ok(product);
            } else {
                return ResponseEntity.badRequest().build();
            }
        } catch (Exception e) {
            log.error("Failed to crawl product: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get price history for a product (for chart display)
     */
    @GetMapping("/{id}/price-history")
    @Operation(summary = "Get price history", description = "Returns price history grouped by bag size for chart display")
    public ResponseEntity<Map<String, List<PriceHistory>>> getPriceHistory(@PathVariable Long id) {
        // Check if product exists
        if (!productRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        Map<String, List<PriceHistory>> history = priceHistoryService.getPriceHistoryBySize(id);

        // Return empty map if no history (chart will not be shown)
        return ResponseEntity.ok(history);
    }

    // DTOs
    public record CrawlProductRequest(
            Long brandId,
            String productUrl
    ) {}

    public record ProductWithBrandDTO(
            Long id,
            String productName,
            String brandName,
            Long brandId,
            String origin,
            String region,
            String process,
            String producer,
            String variety,
            String altitude,
            String sellerUrl,
            java.math.BigDecimal price,
            String currency,
            Boolean inStock
    ) {}

    public record ProductDetailDTO(
            Long id,
            String productName,
            Long brandId,
            String brandName,
            String origin,
            String region,
            String process,
            String producer,
            String variety,
            String altitude,
            String roastLevel,
            String tastingNotesJson,
            String scaFlavorsJson,
            String sellerUrl,
            java.math.BigDecimal price,
            String priceVariantsJson,
            String currency,
            Boolean inStock,
            String rawDescription,
            String descriptionSummary,
            String flavorProfileJson,
            String characterAxesJson
    ) {}

    public record ProductSearchResultDTO(
            Long id,
            String productName,
            String brandName,
            Long brandId,
            String origin,
            String roastLevel,
            java.math.BigDecimal price,
            String priceVariantsJson,
            String currency,
            Boolean inStock
    ) {}

    public record NewProductDTO(
            Long id,
            String productName,
            Long brandId,
            String brandName,
            String origin,
            String roastLevel,
            java.math.BigDecimal price,
            String priceVariantsJson,
            String currency,
            LocalDateTime createdDate
    ) {}

    /**
     * Get social proof stats for a product (love count, avg rating)
     */
    @GetMapping("/{id}/stats")
    @Operation(summary = "Get product social stats", description = "Returns love count and average rating for a product")
    public ResponseEntity<ProductStatsDTO> getProductStats(@PathVariable Long id) {
        if (!productRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        Object[] stats = trackingRepository.getProductStats(id);
        if (stats == null || stats.length == 0) {
            return ResponseEntity.ok(new ProductStatsDTO(0L, null, 0L));
        }

        // JPA may return Object[] directly or wrapped - handle both cases
        Object[] row = stats;
        if (stats[0] instanceof Object[]) {
            row = (Object[]) stats[0];
        }

        // row[0] = loveCount, row[1] = avgRating, row[2] = ratingCount
        Long loveCount = row[0] != null ? ((Number) row[0]).longValue() : 0L;
        Double avgRating = row[1] != null ? ((Number) row[1]).doubleValue() : null;
        Long ratingCount = row[2] != null ? ((Number) row[2]).longValue() : 0L;

        return ResponseEntity.ok(new ProductStatsDTO(loveCount, avgRating, ratingCount));
    }

    public record ProductStatsDTO(
            Long loveCount,
            Double avgRating,
            Long ratingCount
    ) {}
}
