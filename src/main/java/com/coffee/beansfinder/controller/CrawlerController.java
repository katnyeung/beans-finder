package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.dto.CrawlSummary;
import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.scheduler.CrawlerScheduler;
import com.coffee.beansfinder.service.CrawlerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for managing crawler operations
 */
@RestController
@RequestMapping("/api/crawler")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Crawler", description = "Web crawling and data extraction operations")
public class CrawlerController {

    private final CrawlerScheduler crawlerScheduler;
    private final CrawlerService crawlerService;
    private final CoffeeBrandRepository brandRepository;

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
        crawlerScheduler.triggerManualCrawl();
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
     * @return CrawlSummary with stats on new/updated/unchanged/deleted products
     */
    @Operation(
        summary = "Crawl all products from sitemap (incremental)",
        description = "Fetches the brand's sitemap.xml, extracts all product URLs, and uses hash-based change detection to only process new or changed products. Returns summary with new/updated/unchanged/deleted counts and API cost saved."
    )
    @PostMapping("/crawl-from-sitemap")
    public ResponseEntity<?> crawlFromSitemap(
            @Parameter(description = "Brand ID") @RequestParam Long brandId) {

        log.info("Incremental sitemap crawl requested for Brand ID: {}", brandId);

        // Validate brand exists
        CoffeeBrand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + brandId));

        if (brand.getSitemapUrl() == null || brand.getSitemapUrl().isEmpty()) {
            log.error("Brand {} has no sitemap URL configured", brand.getName());
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Brand has no sitemap URL configured"));
        }

        try {
            log.info("Starting incremental sitemap crawl for brand: {} using sitemap: {}",
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

    // DTOs
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
