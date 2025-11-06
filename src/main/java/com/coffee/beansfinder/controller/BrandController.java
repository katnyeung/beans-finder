package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.BrandApproval;
import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.service.BrandApprovalService;
import com.coffee.beansfinder.service.PerplexityApiService;
import com.coffee.beansfinder.service.WebScraperService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * REST API for managing coffee brands
 */
@RestController
@RequestMapping("/api/brands")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Brands", description = "Coffee brand management and approval workflow")
public class BrandController {

    private final CoffeeBrandRepository brandRepository;
    private final BrandApprovalService approvalService;
    private final PerplexityApiService perplexityService;
    private final WebScraperService scraperService;
    private final com.coffee.beansfinder.repository.CoffeeProductRepository productRepository;

    @Operation(
        summary = "Get all brands",
        description = "Returns a list of all coffee brands (approved and pending)"
    )
    @GetMapping
    public List<CoffeeBrand> getAllBrands() {
        return brandRepository.findAll();
    }

    @Operation(
        summary = "Get approved brands",
        description = "Returns only approved and active coffee brands"
    )
    @GetMapping("/approved")
    public List<CoffeeBrand> getApprovedBrands() {
        List<CoffeeBrand> brands = brandRepository.findByApprovedTrue();
        // Populate product count for each brand
        brands.forEach(brand -> {
            long count = productRepository.countByBrandId(brand.getId());
            brand.setProductCount(count);
        });
        return brands;
    }

    @Operation(
        summary = "Get brand by ID",
        description = "Returns a specific coffee brand by its ID"
    )
    @ApiResponse(responseCode = "200", description = "Brand found")
    @ApiResponse(responseCode = "404", description = "Brand not found")
    @GetMapping("/{id}")
    public ResponseEntity<CoffeeBrand> getBrandById(
            @Parameter(description = "Brand ID") @PathVariable Long id) {
        return brandRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Submit new brand for approval
     */
    @PostMapping("/submit")
    public ResponseEntity<BrandApproval> submitBrand(@RequestBody BrandSubmissionRequest request) {
        try {
            BrandApproval approval = approvalService.submitBrandForApproval(
                    request.name,
                    request.website,
                    request.sitemapUrl,
                    request.country,
                    request.description,
                    request.submittedBy,
                    request.submissionNotes
            );
            return ResponseEntity.ok(approval);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get pending brand approvals
     */
    @GetMapping("/approvals/pending")
    public List<BrandApproval> getPendingApprovals() {
        return approvalService.getPendingApprovals();
    }

    /**
     * Approve a brand
     */
    @PostMapping("/approvals/{id}/approve")
    public ResponseEntity<Void> approveBrand(
            @PathVariable Long id,
            @RequestBody ApprovalDecisionRequest request) {
        try {
            approvalService.approveBrand(id, request.reviewedBy, request.reviewNotes);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to approve brand: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Reject a brand
     */
    @PostMapping("/approvals/{id}/reject")
    public ResponseEntity<Void> rejectBrand(
            @PathVariable Long id,
            @RequestBody ApprovalDecisionRequest request) {
        try {
            approvalService.rejectBrand(id, request.reviewedBy, request.reviewNotes);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to reject brand: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Generate a list of coffee brands using Perplexity AI
     */
    @Operation(
        summary = "Generate brands list with details",
        description = "Uses Perplexity AI to generate a list of specialty coffee roasters with full details (website, sitemap, etc.). Automatically excludes brands already in the database. You can also provide suggested brand names (comma-separated)."
    )
    @GetMapping("/generate-list")
    public ResponseEntity<DetailedBrandsListResponse> generateBrandsList(
            @Parameter(description = "Country (e.g., UK, US)")
            @RequestParam(defaultValue = "UK") String country,
            @Parameter(description = "Number of brands to generate")
            @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "Include full details (slower but more complete)")
            @RequestParam(defaultValue = "true") boolean includeDetails,
            @Parameter(description = "Exclude existing brands from results")
            @RequestParam(defaultValue = "true") boolean excludeExisting,
            @Parameter(description = "Suggested brand names (comma-separated, e.g., 'Pact Coffee,Origin Coffee,RAVE Coffee')")
            @RequestParam(required = false) String suggestedBrands) {

        log.info("Generating brands list for country: {} (limit: {}, includeDetails: {}, excludeExisting: {}, suggestedBrands: {})",
                 country, limit, includeDetails, excludeExisting, suggestedBrands);

        try {
            // Get existing brand names to exclude
            List<String> existingBrands = new ArrayList<>();
            if (excludeExisting) {
                existingBrands = brandRepository.findAll().stream()
                        .map(CoffeeBrand::getName)
                        .toList();
                log.info("Found {} existing brands to exclude", existingBrands.size());
            }

            // Parse suggested brands (if provided)
            List<String> suggestedBrandsList = new ArrayList<>();
            if (suggestedBrands != null && !suggestedBrands.trim().isEmpty()) {
                suggestedBrandsList = java.util.Arrays.stream(suggestedBrands.split(","))
                        .map(String::trim)
                        .filter(name -> !name.isEmpty())
                        .toList();
                log.info("Using {} suggested brands as guidelines: {}", suggestedBrandsList.size(), suggestedBrandsList);
            }

            // Get list of brand names from Perplexity (with suggested brands as guidelines)
            List<String> brandNames = perplexityService.generateBrandsList(
                    country, limit, existingBrands, suggestedBrandsList);
            log.info("Generated {} brand names from Perplexity", brandNames.size());

            if (brandNames.isEmpty()) {
                return ResponseEntity.status(500)
                        .body(new DetailedBrandsListResponse(List.of(), 0, 0, existingBrands.size(),
                              "No brands to process (all filtered out or generation failed)"));
            }

            if (!includeDetails) {
                // Return just names (fast)
                List<BrandDetailDto> simpleBrands = brandNames.stream()
                        .map(name -> new BrandDetailDto(name, null, null, null, null))
                        .toList();
                return ResponseEntity.ok(new DetailedBrandsListResponse(
                        simpleBrands, brandNames.size(), brandNames.size(), existingBrands.size(),
                        "Success (names only)"));
            }

            // Discover details for each brand (slower)
            List<BrandDetailDto> detailedBrands = new java.util.ArrayList<>();
            int successCount = 0;

            for (String brandName : brandNames) {
                try {
                    log.info("Discovering details for: {}", brandName);
                    PerplexityApiService.BrandDetails details = perplexityService.discoverBrandDetails(brandName);

                    if (details != null && details.name != null) {
                        // Auto-resolve product sitemap if main sitemap was returned
                        String resolvedSitemapUrl = details.sitemapUrl;
                        if (resolvedSitemapUrl != null && !resolvedSitemapUrl.isEmpty()) {
                            log.info("Checking if {} is a sitemap index", resolvedSitemapUrl);
                            List<String> productSitemaps = scraperService.extractProductSitemapUrls(resolvedSitemapUrl);

                            if (!productSitemaps.isEmpty()) {
                                // Use first product sitemap (most common case is sitemap_products_1.xml)
                                resolvedSitemapUrl = productSitemaps.get(0);
                                log.info("Auto-resolved to product sitemap: {}", resolvedSitemapUrl);
                            } else {
                                log.info("No product sitemap found in index, using main sitemap: {}", resolvedSitemapUrl);
                            }
                        }

                        detailedBrands.add(new BrandDetailDto(
                                details.name,
                                details.website,
                                resolvedSitemapUrl,  // Use resolved sitemap URL
                                details.country,
                                details.description
                        ));
                        successCount++;
                    } else {
                        // Add with just name if details not found
                        detailedBrands.add(new BrandDetailDto(brandName, null, null, null, "Details not found"));
                    }

                    // Small delay to avoid rate limiting
                    Thread.sleep(1000);

                } catch (Exception e) {
                    log.error("Failed to get details for {}: {}", brandName, e.getMessage());
                    detailedBrands.add(new BrandDetailDto(brandName, null, null, null, "Error: " + e.getMessage()));
                }
            }

            log.info("Successfully discovered details for {}/{} brands", successCount, brandNames.size());
            return ResponseEntity.ok(new DetailedBrandsListResponse(
                    detailedBrands,
                    brandNames.size(),
                    successCount,
                    existingBrands.size(),
                    String.format("Success: %d/%d brands with full details (excluded %d existing)",
                                  successCount, brandNames.size(), existingBrands.size())
            ));

        } catch (Exception e) {
            log.error("Error generating brands list: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(new DetailedBrandsListResponse(List.of(), 0, 0, 0, "Error: " + e.getMessage()));
        }
    }

    /**
     * Bulk submit multiple brands at once
     */
    @Operation(
        summary = "Bulk submit brands",
        description = "Submit multiple brands at once for approval. Accepts the JSON output from /generate-list endpoint."
    )
    @PostMapping("/bulk-submit")
    public ResponseEntity<BulkSubmitResponse> bulkSubmitBrands(@RequestBody BulkSubmitRequest request) {

        log.info("Bulk submit requested for {} brands by {}", request.brands.size(), request.submittedBy);

        List<BulkSubmitResult> results = new ArrayList<>();
        int successCount = 0;
        int skippedCount = 0;
        int errorCount = 0;

        for (BrandDetailDto brandDetail : request.brands) {
            try {
                // Check if brand already exists
                if (brandRepository.existsByName(brandDetail.name())) {
                    log.warn("Brand already exists, skipping: {}", brandDetail.name());
                    results.add(new BulkSubmitResult(
                            brandDetail.name(),
                            "skipped",
                            null,
                            "Brand already exists"
                    ));
                    skippedCount++;
                    continue;
                }

                // Submit brand for approval
                BrandApproval approval = approvalService.submitBrandForApproval(
                        brandDetail.name(),
                        brandDetail.website(),
                        brandDetail.sitemapUrl(),
                        brandDetail.country(),
                        brandDetail.description(),
                        request.submittedBy != null ? request.submittedBy : "bulk-submit",
                        "Bulk submitted via API"
                );

                results.add(new BulkSubmitResult(
                        brandDetail.name(),
                        "success",
                        approval.getId(),
                        "Submitted for approval"
                ));
                successCount++;
                log.info("Successfully submitted brand: {} (Approval ID: {})", brandDetail.name(), approval.getId());

            } catch (Exception e) {
                log.error("Failed to submit brand {}: {}", brandDetail.name(), e.getMessage());
                results.add(new BulkSubmitResult(
                        brandDetail.name(),
                        "error",
                        null,
                        "Error: " + e.getMessage()
                ));
                errorCount++;
            }
        }

        log.info("Bulk submit completed: {} success, {} skipped, {} errors",
                 successCount, skippedCount, errorCount);

        return ResponseEntity.ok(new BulkSubmitResponse(
                results,
                request.brands.size(),
                successCount,
                skippedCount,
                errorCount,
                String.format("Processed %d brands: %d submitted, %d skipped, %d errors",
                              request.brands.size(), successCount, skippedCount, errorCount)
        ));
    }

    /**
     * Auto-setup a brand using Perplexity AI to discover details
     */
    @Operation(
        summary = "Setup brand by name",
        description = "Uses Perplexity AI to automatically discover and create a brand with website, sitemap, and description"
    )
    @PostMapping("/auto-setup")
    public ResponseEntity<?> setupBrandByName(@RequestBody AutoSetupRequest request) {

        log.info("Auto-setup requested for brand: {}", request.brandName);

        try {
            // Check if brand already exists
            if (brandRepository.existsByName(request.brandName)) {
                log.warn("Brand already exists: {}", request.brandName);
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Brand already exists: " + request.brandName));
            }

            // Discover brand details using Perplexity
            PerplexityApiService.BrandDetails details = perplexityService.discoverBrandDetails(request.brandName);

            if (details == null || details.name == null) {
                log.error("Failed to discover brand details for: {}", request.brandName);
                return ResponseEntity.status(404)
                        .body(new ErrorResponse("Brand not found or could not retrieve details: " + request.brandName));
            }

            // Create brand submission using discovered details
            BrandApproval approval = approvalService.submitBrandForApproval(
                    details.name,
                    details.website,
                    details.sitemapUrl,
                    details.country,
                    details.description,
                    request.submittedBy != null ? request.submittedBy : "auto-setup",
                    "Auto-discovered using Perplexity AI"
            );

            log.info("Successfully auto-setup brand: {} (Approval ID: {})", details.name, approval.getId());

            return ResponseEntity.ok(new AutoSetupResponse(
                    approval.getId(),
                    details.name,
                    details.website,
                    details.sitemapUrl,
                    details.country,
                    details.description,
                    "Brand auto-setup successful. Pending approval."
            ));

        } catch (Exception e) {
            log.error("Error during auto-setup for brand {}: {}", request.brandName, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(new ErrorResponse("Auto-setup failed: " + e.getMessage()));
        }
    }

    // DTOs
    public record BrandSubmissionRequest(
            String name,
            String website,
            String sitemapUrl,
            String country,
            String description,
            String submittedBy,
            String submissionNotes
    ) {}

    public record ApprovalDecisionRequest(
            String reviewedBy,
            String reviewNotes
    ) {}

    public record BrandDetailDto(
            String name,
            String website,
            String sitemapUrl,
            String country,
            String description
    ) {}

    public record DetailedBrandsListResponse(
            List<BrandDetailDto> brands,
            int totalRequested,
            int totalWithDetails,
            int totalExcluded,
            String message
    ) {}

    public record BulkSubmitRequest(
            List<BrandDetailDto> brands,
            String submittedBy
    ) {}

    public record BulkSubmitResult(
            String brandName,
            String status,  // "success", "skipped", "error"
            Long approvalId,
            String message
    ) {}

    public record BulkSubmitResponse(
            List<BulkSubmitResult> results,
            int totalProcessed,
            int successCount,
            int skippedCount,
            int errorCount,
            String message
    ) {}

    public record AutoSetupRequest(
            String brandName,
            String submittedBy
    ) {}

    public record AutoSetupResponse(
            Long approvalId,
            String name,
            String website,
            String sitemapUrl,
            String country,
            String description,
            String message
    ) {}

    public record ErrorResponse(String message) {}
}
