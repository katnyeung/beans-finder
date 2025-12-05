package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.dto.CrawlSummary;
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
 * REST API for managing crawler operations
 */
@RestController
@RequestMapping("/api/crawler")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Crawler", description = "Web crawling and data extraction operations")
public class CrawlerController {

    private final CrawlerService crawlerService;
    private final CoffeeBrandRepository brandRepository;
    private final CoffeeProductRepository productRepository;

    /**
     * Trigger manual crawl of all brands
     */
    @Operation(
        summary = "Trigger manual crawl",
        description = "Manually trigger crawling for all brands that need updating"
    )
    @PostMapping("/trigger")
    public String triggerCrawl() {
        log.info("Manual crawl triggered via API");
        crawlerService.crawlAllBrands();
        return "Crawl triggered successfully";
    }

    /**
     * Retry failed products
     */
    @Operation(
        summary = "Retry failed products",
        description = "Retry crawling for all products with error status"
    )
    @PostMapping("/retry-failed")
    public String retryFailed() {
        log.info("Retry failed products triggered via API");
        crawlerService.retryFailedProducts();
        return "Retry triggered successfully";
    }

    /**
     * Crawl all products from brand's sitemap using incremental hash-based change detection.
     * Only calls OpenAI for new or changed products, saving API costs.
     *
     * @param brandId The brand ID to crawl products for
     * @param force If true, clears content hashes to force OpenAI re-extraction (rebuilds flavor profiles)
     * @return CrawlSummary with stats on new/updated/unchanged/deleted products
     */
    @Operation(
        summary = "Crawl all products from sitemap (incremental)",
        description = "Fetches the brand's sitemap.xml, extracts all product URLs, and uses hash-based change detection to only process new or changed products. Set force=true to clear hashes and force OpenAI re-extraction (rebuilds flavor profiles)."
    )
    @PostMapping("/crawl-from-sitemap")
    public ResponseEntity<?> crawlFromSitemap(
            @Parameter(description = "Brand ID") @RequestParam Long brandId,
            @Parameter(description = "Force re-extraction by clearing content hashes") @RequestParam(defaultValue = "false") boolean force) {

        log.info("Sitemap crawl requested for Brand ID: {} (force={})", brandId, force);

        // Validate brand exists
        CoffeeBrand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + brandId));

        if (brand.getSitemapUrl() == null || brand.getSitemapUrl().isEmpty()) {
            log.error("Brand {} has no sitemap URL configured", brand.getName());
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Brand has no sitemap URL configured"));
        }

        try {
            // If force=true, clear content hashes to force OpenAI re-extraction
            if (force) {
                int cleared = productRepository.clearContentHashByBrandId(brandId);
                log.info("Force mode: cleared {} content hashes for brand {}", cleared, brand.getName());
            }

            log.info("Starting sitemap crawl for brand: {} using sitemap: {}",
                     brand.getName(), brand.getSitemapUrl());

            CrawlSummary summary = crawlerService.crawlBrandFromSitemap(brand);

            return ResponseEntity.ok(summary);

        } catch (Exception e) {
            log.error("Unexpected error crawling from sitemap: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(new ErrorResponse("Unexpected error: " + e.getMessage()));
        }
    }

    /**
     * Crawl all products for a brand using Perplexity AI
     * @param brandId The brand ID to crawl products for
     * @return Summary of products discovered and saved
     */
    @Operation(
        summary = "Crawl brand products via Perplexity",
        description = "Uses Perplexity AI to discover all coffee products from a brand's website and saves them to the database"
    )
    @PostMapping("/crawl-product")
    public ResponseEntity<?> crawlBrandProducts(@Parameter(description = "Brand ID") @RequestParam Long brandId) {

        log.info("Perplexity product discovery requested for Brand ID: {}", brandId);

        // Validate brand exists
        CoffeeBrand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + brandId));

        try {
            log.info("Discovering products for brand: {} using Perplexity AI", brand.getName());

            // Use Perplexity to discover all products for this brand
            crawlerService.discoverAndCrawlProducts(brand);

            return ResponseEntity.ok(new CrawlProductsResponse(
                    "Product discovery initiated for " + brand.getName(),
                    brand.getWebsite(),
                    "Check logs for detailed progress"
            ));

        } catch (Exception e) {
            log.error("Unexpected error during product discovery: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(new ErrorResponse("Unexpected error: " + e.getMessage()));
        }
    }

    /**
     * Batch crawl brands from sitemap, processing oldest first.
     * Skips brands already crawled today.
     *
     * @param batchSize Number of brands to process (default 5)
     * @param force If true, clears content hashes to force OpenAI re-extraction
     * @return Summary of batch crawl results
     */
    @Operation(
        summary = "Batch crawl brands from sitemap",
        description = "Processes brands not crawled today, oldest first. Each brand is crawled synchronously one by one."
    )
    @PostMapping("/batch-crawl")
    public ResponseEntity<?> batchCrawl(
            @Parameter(description = "Number of brands to process") @RequestParam(defaultValue = "5") int batchSize,
            @Parameter(description = "Force re-extraction by clearing content hashes") @RequestParam(defaultValue = "false") boolean force) {

        log.info("Batch crawl requested: batchSize={}, force={}", batchSize, force);

        // Get brands not crawled today, oldest first
        List<CoffeeBrand> brands = brandRepository.findBrandsNotCrawledToday();

        if (brands.isEmpty()) {
            log.info("No brands need crawling today");
            return ResponseEntity.ok(new BatchCrawlResponse(
                    "No brands need crawling",
                    0, 0, 0, List.of()
            ));
        }

        // Limit to batch size
        List<CoffeeBrand> batch = brands.stream().limit(batchSize).toList();
        log.info("Processing {} brands (out of {} pending)", batch.size(), brands.size());

        List<BrandCrawlResult> results = new java.util.ArrayList<>();
        int success = 0;
        int failed = 0;

        for (CoffeeBrand brand : batch) {
            log.info("=== Crawling brand: {} (ID: {}, lastCrawl: {}) ===",
                    brand.getName(), brand.getId(), brand.getLastCrawlDate());

            try {
                // Clear hashes if force mode
                if (force) {
                    int cleared = productRepository.clearContentHashByBrandId(brand.getId());
                    log.info("Force mode: cleared {} content hashes for {}", cleared, brand.getName());
                }

                // Crawl from sitemap
                CrawlSummary summary = crawlerService.crawlBrandFromSitemap(brand);

                results.add(new BrandCrawlResult(
                        brand.getId(),
                        brand.getName(),
                        "success",
                        summary.getNewProducts(),
                        summary.getUpdatedProducts(),
                        summary.getUnchangedProducts(),
                        null
                ));
                success++;

                log.info("✅ Completed {}: new={}, updated={}, unchanged={}",
                        brand.getName(), summary.getNewProducts(), summary.getUpdatedProducts(), summary.getUnchangedProducts());

            } catch (Exception e) {
                log.error("❌ Failed to crawl {}: {}", brand.getName(), e.getMessage());
                results.add(new BrandCrawlResult(
                        brand.getId(),
                        brand.getName(),
                        "failed",
                        0, 0, 0,
                        e.getMessage()
                ));
                failed++;
            }
        }

        log.info("Batch crawl complete: {} success, {} failed", success, failed);

        return ResponseEntity.ok(new BatchCrawlResponse(
                "Batch crawl complete",
                batch.size(),
                success,
                failed,
                results
        ));
    }

    // DTOs
    public record BatchCrawlResponse(
            String message,
            int totalProcessed,
            int success,
            int failed,
            List<BrandCrawlResult> results
    ) {}

    public record BrandCrawlResult(
            Long brandId,
            String brandName,
            String status,
            int newProducts,
            int updatedProducts,
            int unchangedProducts,
            String error
    ) {}

    public record CrawlProductsResponse(
            String message,
            String brandWebsite,
            String status
    ) {}

    public record SitemapCrawlResponse(
            String message,
            String sitemapUrl,
            String status
    ) {}

    public record ErrorResponse(String message) {}
}
