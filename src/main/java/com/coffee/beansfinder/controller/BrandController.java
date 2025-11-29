package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.entity.LocationCoordinates;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.service.KnowledgeGraphService;
import com.coffee.beansfinder.service.NominatimGeolocationService;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * REST API for managing coffee brands
 */
@RestController
@RequestMapping("/api/brands")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Brands", description = "Coffee brand management")
public class BrandController {

    private final CoffeeBrandRepository brandRepository;
    private final PerplexityApiService perplexityService;
    private final WebScraperService scraperService;
    private final com.coffee.beansfinder.repository.CoffeeProductRepository productRepository;
    private final NominatimGeolocationService geolocationService;
    private final KnowledgeGraphService knowledgeGraphService;

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
     * Create a new brand
     */
    @Operation(
        summary = "Create new brand",
        description = "Create a new coffee brand (pending approval by default)"
    )
    @PostMapping
    public ResponseEntity<CoffeeBrand> createBrand(@RequestBody BrandSubmissionRequest request) {
        try {
            // Check if brand already exists
            if (brandRepository.existsByName(request.name)) {
                log.warn("Brand already exists: {}", request.name);
                return ResponseEntity.badRequest().build();
            }

            // Create brand entity (pending approval by default)
            CoffeeBrand brand = CoffeeBrand.builder()
                    .name(request.name)
                    .website(request.website)
                    .sitemapUrl(request.sitemapUrl)
                    .country(request.country)
                    .city(request.city)
                    .address(request.address)
                    .postcode(request.postcode)
                    .description(request.description)
                    .latitude(request.latitude)
                    .longitude(request.longitude)
                    .coordinatesValidated(request.latitude != null && request.longitude != null)
                    .status("pending_approval")
                    .approved(false)
                    .build();

            brand = brandRepository.save(brand);
            log.info("Created brand pending approval: {} (ID: {})", request.name, brand.getId());

            return ResponseEntity.ok(brand);
        } catch (Exception e) {
            log.error("Failed to create brand: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get pending brands
     */
    @Operation(
        summary = "Get pending brands",
        description = "Returns brands pending approval"
    )
    @GetMapping("/pending")
    public List<CoffeeBrand> getPendingBrands() {
        return brandRepository.findByApprovedFalse();
    }

    /**
     * Approve a brand
     */
    @Operation(
        summary = "Approve brand",
        description = "Approve a pending brand"
    )
    @PostMapping("/{id}/approve")
    public ResponseEntity<CoffeeBrand> approveBrand(
            @PathVariable Long id,
            @RequestBody(required = false) ApprovalDecisionRequest request) {
        try {
            CoffeeBrand brand = brandRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + id));

            brand.setApproved(true);
            brand.setStatus("active");
            brand.setApprovedDate(LocalDateTime.now());
            if (request != null && request.reviewedBy != null) {
                brand.setApprovedBy(request.reviewedBy);
            }

            brand = brandRepository.save(brand);
            log.info("Approved brand: {} (ID: {})", brand.getName(), brand.getId());

            return ResponseEntity.ok(brand);
        } catch (Exception e) {
            log.error("Failed to approve brand: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Reject a brand
     */
    @Operation(
        summary = "Reject brand",
        description = "Reject a pending brand (sets status to rejected)"
    )
    @PostMapping("/{id}/reject")
    public ResponseEntity<CoffeeBrand> rejectBrand(
            @PathVariable Long id,
            @RequestBody(required = false) ApprovalDecisionRequest request) {
        try {
            CoffeeBrand brand = brandRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + id));

            brand.setStatus("rejected");
            brand = brandRepository.save(brand);
            log.info("Rejected brand: {} (ID: {})", brand.getName(), brand.getId());

            return ResponseEntity.ok(brand);
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
                        .map(name -> new BrandDetailDto(name, null, null, null, null, null, null, null, null, null))
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

                        // Geocode location with fallback: try address first, then city
                        Double latitude = null;
                        Double longitude = null;
                        String locationToGeocode = null;

                        if (details.address != null && !details.address.isBlank()) {
                            locationToGeocode = details.address;
                        } else if (details.city != null && !details.city.isBlank()) {
                            locationToGeocode = details.city;
                        }

                        if (locationToGeocode != null && details.country != null) {
                            try {
                                log.info("Geocoding: {}, {}", locationToGeocode, details.country);
                                com.coffee.beansfinder.entity.LocationCoordinates coords =
                                    geolocationService.geocode(locationToGeocode, details.country, null);
                                if (coords != null) {
                                    latitude = coords.getLatitude();
                                    longitude = coords.getLongitude();
                                    log.info("Geocoded {} to ({}, {})", locationToGeocode, latitude, longitude);
                                }
                            } catch (Exception e) {
                                log.warn("Failed to geocode {}, {}: {}", locationToGeocode, details.country, e.getMessage());
                            }
                        }

                        detailedBrands.add(new BrandDetailDto(
                                details.name,
                                details.website,
                                resolvedSitemapUrl,  // Use resolved sitemap URL
                                details.country,
                                details.city,
                                details.address,
                                details.postcode,
                                details.description,
                                latitude,
                                longitude
                        ));
                        successCount++;
                    } else {
                        // Add with just name if details not found
                        detailedBrands.add(new BrandDetailDto(brandName, null, null, null, null, null, null, "Details not found", null, null));
                    }

                    // Small delay to avoid rate limiting
                    Thread.sleep(1000);

                } catch (Exception e) {
                    log.error("Failed to get details for {}: {}", brandName, e.getMessage());
                    detailedBrands.add(new BrandDetailDto(brandName, null, null, null, null, null, null, "Error: " + e.getMessage(), null, null));
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
        description = "Submit multiple brands at once. Accepts the JSON output from /generate-list endpoint."
    )
    @PostMapping("/bulk-submit")
    public ResponseEntity<BulkSubmitResponse> bulkSubmitBrands(@RequestBody BulkSubmitRequest request) {

        log.info("Bulk submit requested for {} brands", request.brands.size());

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

                // Create brand (pending approval by default)
                CoffeeBrand brand = CoffeeBrand.builder()
                        .name(brandDetail.name())
                        .website(brandDetail.website())
                        .sitemapUrl(brandDetail.sitemapUrl())
                        .country(brandDetail.country())
                        .city(brandDetail.city())
                        .address(brandDetail.address())
                        .postcode(brandDetail.postcode())
                        .description(brandDetail.description())
                        .latitude(brandDetail.latitude())
                        .longitude(brandDetail.longitude())
                        .coordinatesValidated(brandDetail.latitude() != null && brandDetail.longitude() != null)
                        .status("pending_approval")
                        .approved(false)
                        .build();

                brand = brandRepository.save(brand);

                results.add(new BulkSubmitResult(
                        brandDetail.name(),
                        "success",
                        brand.getId(),
                        "Created (pending approval)"
                ));
                successCount++;
                log.info("Successfully created brand: {} (ID: {})", brandDetail.name(), brand.getId());

            } catch (Exception e) {
                log.error("Failed to create brand {}: {}", brandDetail.name(), e.getMessage());
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
                String.format("Processed %d brands: %d created, %d skipped, %d errors",
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

            // Create brand using discovered details (pending approval by default)
            CoffeeBrand brand = CoffeeBrand.builder()
                    .name(details.name)
                    .website(details.website)
                    .sitemapUrl(details.sitemapUrl)
                    .country(details.country)
                    .city(details.city)
                    .address(details.address)
                    .postcode(details.postcode)
                    .description(details.description)
                    .status("pending_approval")
                    .approved(false)
                    .build();

            brand = brandRepository.save(brand);
            log.info("Successfully auto-setup brand: {} (ID: {})", details.name, brand.getId());

            return ResponseEntity.ok(new AutoSetupResponse(
                    brand.getId(),
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
            String city,
            String address,
            String postcode,
            String description,
            Double latitude,
            Double longitude
    ) {}

    public record ApprovalDecisionRequest(
            String reviewedBy
    ) {}

    public record BrandDetailDto(
            String name,
            String website,
            String sitemapUrl,
            String country,
            String city,
            String address,
            String postcode,
            String description,
            Double latitude,
            Double longitude
    ) {}

    public record DetailedBrandsListResponse(
            List<BrandDetailDto> brands,
            int totalRequested,
            int totalWithDetails,
            int totalExcluded,
            String message
    ) {}

    public record BulkSubmitRequest(
            List<BrandDetailDto> brands
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
            String brandName
    ) {}

    public record AutoSetupResponse(
            Long brandId,
            String name,
            String website,
            String sitemapUrl,
            String country,
            String description,
            String message
    ) {}

    public record ErrorResponse(String message) {}

    /**
     * DTO for public brand suggestion
     */
    public record SuggestBrandRequest(
            String name,
            String websiteUrl,
            String recaptchaToken
    ) {}

    /**
     * Suggest a brand (public endpoint with reCAPTCHA)
     */
    @Operation(
        summary = "Suggest a brand",
        description = "Public endpoint for users to suggest a new coffee brand. Requires reCAPTCHA verification."
    )
    @PostMapping("/suggest")
    public ResponseEntity<String> suggestBrand(@RequestBody SuggestBrandRequest request) {
        log.info("Brand suggestion received: {} ({})", request.name, request.websiteUrl);

        try {
            // Validate input
            if (request.name == null || request.name.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Brand name is required");
            }
            if (request.websiteUrl == null || request.websiteUrl.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Website URL is required");
            }

            // Validate reCAPTCHA
            if (request.recaptchaToken == null || request.recaptchaToken.isEmpty()) {
                return ResponseEntity.badRequest().body("CAPTCHA verification is required");
            }

            // Verify reCAPTCHA with Google
            boolean captchaValid = verifyRecaptcha(request.recaptchaToken);
            if (!captchaValid) {
                log.warn("reCAPTCHA verification failed for brand suggestion: {}", request.name);
                return ResponseEntity.badRequest().body("CAPTCHA verification failed. Please try again.");
            }

            // Check if brand already exists
            if (brandRepository.existsByName(request.name.trim())) {
                log.warn("Brand already exists: {}", request.name);
                return ResponseEntity.badRequest().body("This brand already exists in our database");
            }

            // Check if website URL already exists
            if (brandRepository.existsByWebsite(request.websiteUrl.trim())) {
                log.warn("Website already exists: {}", request.websiteUrl);
                return ResponseEntity.badRequest().body("A brand with this website already exists");
            }

            // Create brand (pending approval)
            CoffeeBrand brand = CoffeeBrand.builder()
                    .name(request.name.trim())
                    .website(request.websiteUrl.trim())
                    .status("pending_approval")
                    .approved(false)
                    .build();

            brand = brandRepository.save(brand);
            log.info("Brand suggestion saved: {} (ID: {})", brand.getName(), brand.getId());

            return ResponseEntity.ok("Thank you! Your suggestion has been submitted for review.");

        } catch (Exception e) {
            log.error("Error processing brand suggestion: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("An error occurred. Please try again later.");
        }
    }

    /**
     * Verify reCAPTCHA token with Google
     */
    private boolean verifyRecaptcha(String token) {
        try {
            String secretKey = System.getenv("RECAPTCHA_SECRET_KEY");
            if (secretKey == null || secretKey.isEmpty()) {
                // If no secret key configured, allow (development mode)
                log.warn("RECAPTCHA_SECRET_KEY not configured - skipping verification");
                return true;
            }

            String verifyUrl = "https://www.google.com/recaptcha/api/siteverify";
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

            String formData = "secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8")
                    + "&response=" + java.net.URLEncoder.encode(token, "UTF-8");

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(verifyUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(formData))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            // Parse response (simple JSON parsing)
            String body = response.body();
            return body.contains("\"success\": true") || body.contains("\"success\":true");

        } catch (Exception e) {
            log.error("reCAPTCHA verification error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Batch extract addresses for existing brands using Perplexity AI
     */
    @Operation(
        summary = "Extract addresses for existing brands",
        description = "Uses Perplexity AI to extract city, address, and postcode for existing brands and geocode them"
    )
    @PostMapping("/extract-addresses-batch")
    public ResponseEntity<BatchExtractAddressResponse> extractAddressesBatch(
            @Parameter(description = "Force re-extraction even if address already exists")
            @RequestParam(defaultValue = "false") boolean force) {

        log.info("Batch address extraction requested (force={})", force);

        var brands = brandRepository.findAll();
        List<AddressExtractionResult> results = new ArrayList<>();
        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        for (CoffeeBrand brand : brands) {
            // Skip if already has address (unless force=true)
            if (!force && brand.getAddress() != null && !brand.getAddress().isBlank()) {
                log.debug("Skipping {} - already has address", brand.getName());
                results.add(new AddressExtractionResult(
                        brand.getId(),
                        brand.getName(),
                        "skipped",
                        brand.getCity(),
                        brand.getAddress(),
                        brand.getPostcode(),
                        brand.getLatitude(),
                        brand.getLongitude(),
                        "Already has address"
                ));
                skipCount++;
                continue;
            }

            try {
                log.info("Extracting address for: {}", brand.getName());

                // Use Perplexity to discover brand details (including address)
                PerplexityApiService.BrandDetails details = perplexityService.discoverBrandDetails(brand.getName());

                if (details == null || details.name == null) {
                    log.warn("Failed to extract details for: {}", brand.getName());
                    results.add(new AddressExtractionResult(
                            brand.getId(),
                            brand.getName(),
                            "error",
                            null, null, null, null, null,
                            "Failed to extract brand details"
                    ));
                    errorCount++;
                    continue;
                }

                // Update brand with location data
                boolean updated = false;

                log.info("Extracted location data for {}: city='{}', address='{}', postcode='{}'",
                        brand.getName(), details.city, details.address, details.postcode);

                if (details.city != null && !details.city.isBlank()) {
                    brand.setCity(details.city);
                    updated = true;
                }
                if (details.address != null && !details.address.isBlank()) {
                    brand.setAddress(details.address);
                    updated = true;
                }
                if (details.postcode != null && !details.postcode.isBlank()) {
                    brand.setPostcode(details.postcode);
                    updated = true;
                }

                // Geocode using address → city → country fallback
                Double latitude = null;
                Double longitude = null;
                String locationUsed = null;

                if (brand.getAddress() != null && !brand.getAddress().isBlank()) {
                    LocationCoordinates coords = geolocationService.geocode(brand.getAddress(), brand.getCountry(), null);
                    if (coords != null) {
                        latitude = coords.getLatitude();
                        longitude = coords.getLongitude();
                        locationUsed = "address";
                    }
                } else if (brand.getCity() != null && !brand.getCity().isBlank()) {
                    LocationCoordinates coords = geolocationService.geocode(brand.getCity(), brand.getCountry(), null);
                    if (coords != null) {
                        latitude = coords.getLatitude();
                        longitude = coords.getLongitude();
                        locationUsed = "city";
                    }
                } else if (brand.getCountry() != null) {
                    LocationCoordinates coords = geolocationService.geocodeCountry(brand.getCountry());
                    if (coords != null) {
                        latitude = coords.getLatitude();
                        longitude = coords.getLongitude();
                        locationUsed = "country";
                    }
                }

                if (latitude != null && longitude != null) {
                    brand.setLatitude(latitude);
                    brand.setLongitude(longitude);
                    brand.setCoordinatesValidated(true);
                    updated = true;
                }

                if (updated) {
                    brandRepository.save(brand);

                    // Re-sync to Neo4j
                    brand.getProducts().forEach(product -> {
                        try {
                            knowledgeGraphService.syncProductToGraph(product);
                        } catch (Exception e) {
                            log.warn("Failed to sync product to graph: {}", product.getId(), e);
                        }
                    });

                    results.add(new AddressExtractionResult(
                            brand.getId(),
                            brand.getName(),
                            "success",
                            brand.getCity(),
                            brand.getAddress(),
                            brand.getPostcode(),
                            latitude,
                            longitude,
                            "Extracted and geocoded using " + locationUsed
                    ));
                    successCount++;
                } else {
                    results.add(new AddressExtractionResult(
                            brand.getId(),
                            brand.getName(),
                            "no_update",
                            null, null, null, null, null,
                            "No location data found"
                    ));
                    skipCount++;
                }

                // Rate limiting
                Thread.sleep(1000);

            } catch (Exception e) {
                log.error("Failed to extract address for {}: {}", brand.getName(), e.getMessage());
                results.add(new AddressExtractionResult(
                        brand.getId(),
                        brand.getName(),
                        "error",
                        null, null, null, null, null,
                        "Error: " + e.getMessage()
                ));
                errorCount++;
            }
        }

        log.info("Batch address extraction completed: {} success, {} skipped, {} errors",
                successCount, skipCount, errorCount);

        return ResponseEntity.ok(new BatchExtractAddressResponse(
                results,
                brands.size(),
                successCount,
                skipCount,
                errorCount,
                String.format("Processed %d brands: %d updated, %d skipped, %d errors",
                        brands.size(), successCount, skipCount, errorCount)
        ));
    }

    public record AddressExtractionResult(
            Long brandId,
            String brandName,
            String status,
            String city,
            String address,
            String postcode,
            Double latitude,
            Double longitude,
            String message
    ) {}

    public record BatchExtractAddressResponse(
            List<AddressExtractionResult> results,
            int totalBrands,
            int successCount,
            int skipCount,
            int errorCount,
            String message
    ) {}
}
