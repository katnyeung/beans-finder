package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.coffee.beansfinder.service.CrawlerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    /**
     * Get all products
     */
    @GetMapping
    public List<CoffeeProduct> getAllProducts() {
        return productRepository.findAll();
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
                            product.getCurrency(),
                            product.getInStock(),
                            product.getRawDescription()
                    );
                    return ResponseEntity.ok(dto);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get products by brand
     */
    @GetMapping("/brand/{brandId}")
    public List<CoffeeProduct> getProductsByBrand(@PathVariable Long brandId) {
        return productRepository.findByBrandId(brandId);
    }

    /**
     * Get products by origin
     */
    @GetMapping("/origin/{origin}")
    public List<CoffeeProduct> getProductsByOrigin(@PathVariable String origin) {
        return productRepository.findByOrigin(origin);
    }

    /**
     * Get products by origin (region or country) with brand information (for map display).
     * First tries to match by region (exact match), then falls back to country.
     */
    @GetMapping("/origin/{origin}/with-brand")
    public List<ProductWithBrandDTO> getProductsByOriginWithBrand(@PathVariable String origin) {
        List<CoffeeProduct> products;

        // Try searching by region first (for specific origins like "Santa Clara, Renacimiento, Chiriqui")
        products = productRepository.findByRegionIgnoreCase(origin);

        // If no results, try searching by country (origin field)
        if (products.isEmpty()) {
            products = productRepository.findByOrigin(origin);
        }

        return products.stream()
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
     * Request update for a specific product (re-crawl)
     */
    @PostMapping("/{id}/request-update")
    @Operation(summary = "Request product update", description = "Re-crawl the product page to update product information")
    public ResponseEntity<String> requestProductUpdate(@PathVariable Long id) {
        try {
            // Find the product
            CoffeeProduct product = productRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found"));

            // Check if product has a seller URL
            if (product.getSellerUrl() == null || product.getSellerUrl().isEmpty()) {
                return ResponseEntity.badRequest().body("Product has no seller URL to crawl");
            }

            // Re-crawl the product, passing the existing product ID
            log.info("Re-crawling product {} (ID: {}) from URL: {}",
                    product.getProductName(), id, product.getSellerUrl());

            CoffeeProduct updatedProduct = crawlerService.crawlProduct(
                    product.getBrand(),
                    product.getSellerUrl(),
                    id  // Pass the product ID to update the existing record
            );

            if (updatedProduct != null) {
                return ResponseEntity.ok("Product updated successfully");
            } else {
                return ResponseEntity.status(500).body("Failed to update product");
            }
        } catch (Exception e) {
            log.error("Failed to update product {}: {}", id, e.getMessage());
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
            String currency,
            Boolean inStock,
            String rawDescription
    ) {}
}
