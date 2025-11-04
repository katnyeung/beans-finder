package com.coffee.beansfinder.controller;

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
     * Crawl a single product URL using Perplexity AI
     * @param brandId The brand ID to associate the product with
     * @param request The request containing the product URL to crawl
     * @return The extracted product data
     */
    @Operation(
        summary = "Crawl single product URL",
        description = "Fetches a product page, uses Perplexity AI to extract structured data, and saves it to the database"
    )
    @PostMapping("/crawl-product")
    public ResponseEntity<?> crawlSingleProduct(
            @Parameter(description = "Brand ID") @RequestParam Long brandId,
            @RequestBody CrawlProductRequest request) {

        log.info("Manual product crawl requested for URL: {} (Brand ID: {})", request.productUrl, brandId);

        // Validate brand exists
        CoffeeBrand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + brandId));

        try {
            // Crawl the product
            CoffeeProduct product = crawlerService.crawlProduct(brand, request.productUrl);

            if (product == null) {
                log.error("Failed to crawl product from URL: {}", request.productUrl);
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Failed to crawl product. Check URL and try again."));
            }

            if ("error".equals(product.getCrawlStatus())) {
                log.warn("Product crawled with error status: {}", product.getErrorMessage());
                return ResponseEntity.status(500)
                        .body(new ErrorResponse("Error crawling product: " + product.getErrorMessage()));
            }

            log.info("Successfully crawled product: {} (ID: {})", product.getProductName(), product.getId());
            return ResponseEntity.ok(product);

        } catch (Exception e) {
            log.error("Unexpected error crawling product: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(new ErrorResponse("Unexpected error: " + e.getMessage()));
        }
    }

    // DTOs
    public record CrawlProductRequest(
            @Parameter(description = "Product URL to crawl")
            String productUrl
    ) {}

    public record ErrorResponse(String message) {}
}
