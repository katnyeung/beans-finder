package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.CrawlSummary;
import com.coffee.beansfinder.dto.ExtractedProductData;
import com.coffee.beansfinder.dto.SCAFlavorMapping;
import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Main crawler service that orchestrates the crawling process
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CrawlerService {

    private final CoffeeBrandRepository brandRepository;
    private final CoffeeProductRepository productRepository;
    private final WebScraperService scraperService;
    private final PerplexityApiService perplexityService;
    private final SCAFlavorWheelService scaService;
    private final KnowledgeGraphService graphService;
    private final PlaywrightScraperService playwrightService;
    private final OpenAIService openAIService;
    private final ObjectMapper objectMapper;
    private final MapCacheService mapCacheService;
    private final ContentHashService contentHashService;

    @Value("${crawler.update.interval.days:14}")
    private int updateIntervalDays;

    @Value("${crawler.playwright.chunk.size:10}")
    private int playwrightChunkSize;

    /**
     * Crawl all brands that need updating
     */
    public void crawlAllBrands() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(updateIntervalDays);
        List<CoffeeBrand> brandsToUpdate = brandRepository.findBrandsNeedingCrawl(cutoffDate);

        log.info("Found {} brands needing crawl", brandsToUpdate.size());

        for (CoffeeBrand brand : brandsToUpdate) {
            try {
                crawlBrand(brand);
            } catch (Exception e) {
                log.error("Failed to crawl brand {}: {}", brand.getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * Crawl a specific brand
     */
    // NOTE: No @Transactional - processAndSaveProduct handles its own transaction
    public void crawlBrand(CoffeeBrand brand) {
        log.info("Starting crawl for brand: {}", brand.getName());

        if (!brand.getApproved()) {
            log.warn("Brand {} is not approved, skipping crawl", brand.getName());
            return;
        }

        try {
            // Fetch brand's product listing page
            Optional<Document> doc = scraperService.fetchPage(brand.getWebsite());

            if (doc.isEmpty()) {
                log.error("Failed to fetch website for brand: {}", brand.getName());
                return;
            }

            // Extract product information
            String rawContent = scraperService.extractTextContent(doc.get());
            WebScraperService.ProductPageMetadata metadata = scraperService.extractMetadata(doc.get());

            // Use Perplexity to extract structured data
            ExtractedProductData extractedData = perplexityService.extractProductData(
                    rawContent + "\n\nMetadata: " + metadata.description,
                    brand.getName(),
                    brand.getWebsite()
            );

            // Process and save product
            processAndSaveProduct(brand, extractedData, rawContent, brand.getWebsite());

            // Update brand's last crawl date
            brand.setLastCrawlDate(LocalDateTime.now());
            brandRepository.save(brand);

            log.info("Successfully completed crawl for brand: {}", brand.getName());

        } catch (Exception e) {
            log.error("Error crawling brand {}: {}", brand.getName(), e.getMessage(), e);
        }
    }

    /**
     * Discover and crawl all products for a brand using Perplexity AI
     */
    // NOTE: No @Transactional - processAndSaveProduct handles its own transaction
    public void discoverAndCrawlProducts(CoffeeBrand brand) {
        log.info("Starting Perplexity product discovery for brand: {}", brand.getName());

        try {
            // Use Perplexity to discover all products
            List<ExtractedProductData> products = perplexityService.discoverBrandProducts(
                    brand.getName(),
                    brand.getWebsite(),
                    brand.getSitemapUrl()
            );

            if (products.isEmpty()) {
                log.warn("No products discovered for brand: {}", brand.getName());
                return;
            }

            log.info("Discovered {} products for brand: {}", products.size(), brand.getName());

            int successCount = 0;
            int errorCount = 0;

            // Process and save each discovered product
            for (ExtractedProductData productData : products) {
                try {
                    log.info("Processing product: {}", productData.getProductName());

                    // Use product URL from extracted data if available, otherwise fallback to brand website
                    String productUrl = productData.getProductUrl() != null && !productData.getProductUrl().isEmpty()
                            ? productData.getProductUrl()
                            : brand.getWebsite();

                    // Use existing method to process and save
                    CoffeeProduct product = processAndSaveProduct(
                            brand,
                            productData,
                            "Discovered via Perplexity AI",
                            productUrl
                    );

                    if (product != null && !"error".equals(product.getCrawlStatus())) {
                        successCount++;
                        log.info("Successfully saved product: {} (ID: {})",
                                 product.getProductName(), product.getId());
                    } else {
                        errorCount++;
                        log.warn("Failed to save product: {}", productData.getProductName());
                    }

                } catch (Exception e) {
                    errorCount++;
                    log.error("Error processing product {}: {}", productData.getProductName(), e.getMessage());
                }
            }

            // Update brand's last crawl date
            brand.setLastCrawlDate(LocalDateTime.now());
            brandRepository.save(brand);

            log.info("Product discovery completed for brand: {}. Success: {}, Errors: {}",
                     brand.getName(), successCount, errorCount);

        } catch (Exception e) {
            log.error("Error during product discovery for brand {}: {}", brand.getName(), e.getMessage(), e);
        }
    }

    /**
     * Crawl all products from a brand's sitemap using incremental hash-based change detection.
     * Only calls OpenAI for new or changed products, saving API costs.
     *
     * @return CrawlSummary with stats on new/updated/unchanged/deleted products
     */
    // NOTE: No @Transactional here - each product is saved in its own transaction
    // This prevents "idle-in-transaction timeout" during slow OpenAI API calls
    public CrawlSummary crawlBrandFromSitemap(CoffeeBrand brand) {
        log.info("Starting incremental sitemap crawl for brand: {} from {}", brand.getName(), brand.getSitemapUrl());

        CrawlSummary.CrawlSummaryBuilder summaryBuilder = CrawlSummary.builder()
                .brandName(brand.getName());

        if (brand.getSitemapUrl() == null || brand.getSitemapUrl().isEmpty()) {
            log.error("Brand {} has no sitemap URL", brand.getName());
            return summaryBuilder.build();
        }

        int newCount = 0;
        int updatedCount = 0;
        int unchangedCount = 0;
        int deletedCount = 0;

        try {
            // Step 1: Build map of existing products by sellerUrl for efficient lookup
            List<CoffeeProduct> existingProducts = productRepository.findByBrand(brand);
            Map<String, CoffeeProduct> existingByUrl = new HashMap<>();
            for (CoffeeProduct p : existingProducts) {
                if (p.getSellerUrl() != null) {
                    existingByUrl.put(p.getSellerUrl(), p);
                }
            }
            log.info("Found {} existing products for brand: {}", existingByUrl.size(), brand.getName());

            // Step 2: Fetch and filter sitemap to coffee products only (keyword-based, free)
            List<String> productUrls = scraperService.extractProductUrlsFromSitemap(brand.getSitemapUrl());
            log.info("Extracted {} URLs from sitemap (after keyword-based filtering)", productUrls.size());

            if (productUrls.isEmpty()) {
                log.warn("No coffee products found in sitemap for brand: {}", brand.getName());
                return summaryBuilder.totalProcessed(0).build();
            }

            // Track which URLs we've seen in this crawl (for deletion detection)
            Set<String> urlsInSitemap = new HashSet<>(productUrls);

            // Step 3: Process each URL with hash-based change detection
            int totalUrls = productUrls.size();

            for (int i = 0; i < totalUrls; i++) {
                String productUrl = productUrls.get(i);

                // Step 3a: Playwright extracts clean product text
                log.info("[{}/{}] Extracting text with Playwright: {}", i + 1, totalUrls, productUrl);
                String productText = playwrightService.extractProductText(productUrl);

                if (productText == null || productText.isEmpty()) {
                    log.error("Playwright failed to extract text for: {}", productUrl);
                    continue; // Skip this URL, don't fail entire crawl
                }

                // Step 3b: Generate hash of extracted content
                String newHash = contentHashService.generateHash(productText);

                // Step 3c: Check if product exists
                CoffeeProduct existingProduct = existingByUrl.get(productUrl);

                if (existingProduct != null) {
                    // Product exists - check if content changed
                    String existingHash = existingProduct.getContentHash();

                    if (!contentHashService.hasContentChanged(newHash, existingHash)) {
                        // Content unchanged - skip OpenAI, save cost!
                        log.info("⏭️ [{}/{}] UNCHANGED (hash match): {}", i + 1, totalUrls, productUrl);
                        unchangedCount++;
                        continue;
                    }

                    // Content changed - need to re-extract with OpenAI
                    log.info("🔄 [{}/{}] CHANGED (hash mismatch): {}", i + 1, totalUrls, productUrl);
                    ExtractedProductData data = extractWithOpenAI(productText, brand.getName(), productUrl);

                    if (data != null) {
                        processAndSaveProduct(brand, data, productText, productUrl, existingProduct.getId());
                        existingProduct.setContentHash(newHash);
                        productRepository.save(existingProduct);
                        updatedCount++;
                        log.info("✓ [{}/{}] UPDATED: {} (origin: {})", i + 1, totalUrls,
                                data.getProductName(), data.getOrigin());
                    }
                } else {
                    // New product - extract with OpenAI
                    log.info("🆕 [{}/{}] NEW product: {}", i + 1, totalUrls, productUrl);
                    ExtractedProductData data = extractWithOpenAI(productText, brand.getName(), productUrl);

                    if (data != null) {
                        CoffeeProduct newProduct = processAndSaveProduct(brand, data, productText, productUrl, null);
                        if (newProduct != null) {
                            newProduct.setContentHash(newHash);
                            productRepository.save(newProduct);
                            newCount++;
                            log.info("✓ [{}/{}] ADDED: {} (origin: {})", i + 1, totalUrls,
                                    data.getProductName(), data.getOrigin());
                        }
                    }
                }
            }

            // Step 4: Delete products no longer in sitemap
            for (CoffeeProduct existing : existingProducts) {
                if (existing.getSellerUrl() != null && !urlsInSitemap.contains(existing.getSellerUrl())) {
                    log.info("🗑️ DELETING product not in sitemap: {} (ID: {})",
                            existing.getProductName(), existing.getId());
                    try {
                        // Delete from Neo4j first
                        graphService.deleteProductFromGraph(existing.getId());
                    } catch (Exception e) {
                        log.warn("Failed to delete product {} from graph: {}", existing.getId(), e.getMessage());
                    }
                    // Delete from PostgreSQL
                    productRepository.delete(existing);
                    deletedCount++;
                }
            }

            // Update brand's last crawl date
            brand.setLastCrawlDate(LocalDateTime.now());
            brandRepository.save(brand);

            // Build summary
            double costSaved = CrawlSummary.calculateCostSaved(unchangedCount);
            CrawlSummary summary = summaryBuilder
                    .newProducts(newCount)
                    .updatedProducts(updatedCount)
                    .unchangedProducts(unchangedCount)
                    .deletedProducts(deletedCount)
                    .totalProcessed(totalUrls)
                    .apiCostSaved(costSaved)
                    .build();

            log.info("========== CRAWL SUMMARY for {} ==========", brand.getName());
            log.info("  🆕 New products:       {}", newCount);
            log.info("  🔄 Updated products:   {}", updatedCount);
            log.info("  ⏭️ Unchanged products: {}", unchangedCount);
            log.info("  🗑️ Deleted products:   {}", deletedCount);
            log.info("  💰 API cost saved:     ${}", String.format("%.4f", costSaved));
            log.info("==========================================");

            // Rebuild map cache asynchronously after successful crawl
            // This prevents blocking and avoids transaction conflicts with PostgreSQL
            try {
                log.info("Scheduling async map cache rebuild after crawl completion...");
                mapCacheService.rebuildAllCachesAsync();
            } catch (Exception cacheError) {
                log.error("Failed to schedule async cache rebuild: {}", cacheError.getMessage());
            }

            return summary;

        } catch (Exception e) {
            log.error("Error crawling sitemap for brand {}: {}", brand.getName(), e.getMessage(), e);
            return summaryBuilder
                    .newProducts(newCount)
                    .updatedProducts(updatedCount)
                    .unchangedProducts(unchangedCount)
                    .deletedProducts(deletedCount)
                    .build();
        }
    }

    /**
     * Helper method to extract product data with OpenAI
     */
    private ExtractedProductData extractWithOpenAI(String productText, String brandName, String productUrl) {
        try {
            ExtractedProductData data = openAIService.extractFromText(productText, brandName, productUrl);
            if (data == null || data.getProductName() == null) {
                log.error("OpenAI failed to extract data for: {}", productUrl);
                return null;
            }
            return data;
        } catch (Exception e) {
            log.error("OpenAI extraction error for {}: {}", productUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Crawl a specific product URL using Playwright + OpenAI
     */
    // NOTE: No @Transactional here - processAndSaveProduct handles its own transaction
    // This prevents "idle-in-transaction timeout" during slow Playwright + OpenAI calls
    public CoffeeProduct crawlProduct(CoffeeBrand brand, String productUrl) {
        return crawlProduct(brand, productUrl, null);
    }

    /**
     * Crawl a specific product URL using Playwright + OpenAI
     * @param brand The brand
     * @param productUrl The product URL
     * @param existingProductId Optional existing product ID to update (null to create new)
     */
    // NOTE: No @Transactional here - processAndSaveProduct handles its own transaction
    public CoffeeProduct crawlProduct(CoffeeBrand brand, String productUrl, Long existingProductId) {
        log.info("Crawling product: {} from brand: {}", productUrl, brand.getName());

        try {
            // Use Playwright to extract clean product text (removes scripts, styles, nav, footer)
            log.info("Extracting product text with Playwright + OpenAI: {}", productUrl);
            String productText = playwrightService.extractProductText(productUrl);

            if (productText == null || productText.isEmpty()) {
                log.error("Playwright failed to extract product text: {}", productUrl);
                return null;
            }

            log.info("Product text extracted successfully ({} chars), sending to OpenAI", productText.length());

            // Use OpenAI to extract structured data from clean product text
            ExtractedProductData extractedData = openAIService.extractFromText(
                    productText,
                    brand.getName(),
                    productUrl
            );

            if (extractedData == null || extractedData.getProductName() == null) {
                log.error("OpenAI extraction failed for: {}", productUrl);
                return null;
            }

            log.info("Successfully extracted product: {}", extractedData.getProductName());

            // Process and save (with existing product ID if provided)
            return processAndSaveProduct(brand, extractedData, productText, productUrl, existingProductId);

        } catch (Exception e) {
            log.error("Error crawling product {}: {}", productUrl, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Save extracted products in batch to prevent connection timeout
     * Called during chunked processing of Playwright + Perplexity fallback
     */
    @Transactional
    private void saveExtractedProducts(CoffeeBrand brand, List<ExtractedProductData> products) {
        log.info("Saving batch of {} products for brand: {}", products.size(), brand.getName());

        int successCount = 0;
        int errorCount = 0;

        for (ExtractedProductData productData : products) {
            try {
                String productUrl = productData.getProductUrl() != null && !productData.getProductUrl().isEmpty()
                        ? productData.getProductUrl()
                        : brand.getWebsite();

                String rawContent = productData.getRawDescription() != null
                        ? productData.getRawDescription()
                        : "";

                CoffeeProduct product = processAndSaveProduct(
                        brand,
                        productData,
                        rawContent,
                        productUrl
                );

                if (product != null) {
                    successCount++;
                    log.debug("✓ Saved: {}", product.getProductName());
                }

            } catch (Exception e) {
                errorCount++;
                log.error("✗ Failed to save product: {} - {}",
                        productData.getProductName(), e.getMessage());
            }
        }

        log.info("Batch save completed: {} success, {} errors", successCount, errorCount);
    }

    /**
     * Check if extracted data is mostly empty (indicates scraping failure)
     */
    private boolean isMostlyEmpty(ExtractedProductData data) {
        if (data == null) {
            return true;
        }

        int emptyCount = 0;
        int totalFields = 7; // Count important fields

        if (data.getProductName() == null || data.getProductName().isEmpty()) emptyCount++;
        if (data.getOrigin() == null || data.getOrigin().isEmpty()) emptyCount++;
        if (data.getProcess() == null || data.getProcess().isEmpty()) emptyCount++;
        if (data.getVariety() == null || data.getVariety().isEmpty()) emptyCount++;
        if (data.getTastingNotes() == null || data.getTastingNotes().isEmpty()) emptyCount++;
        if (data.getPrice() == null) emptyCount++;
        if (data.getRawDescription() == null || data.getRawDescription().isEmpty()) emptyCount++;

        // If 5 or more out of 7 fields are empty, consider it mostly empty
        return emptyCount >= 5;
    }

    /**
     * Process extracted data and save to database
     * Each product is saved in its own transaction to prevent timeout during slow API calls
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public CoffeeProduct processAndSaveProduct(
            CoffeeBrand brand,
            ExtractedProductData extractedData,
            String rawContent,
            String url) {
        return processAndSaveProductInternal(brand, extractedData, rawContent, url, null);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public CoffeeProduct processAndSaveProduct(
            CoffeeBrand brand,
            ExtractedProductData extractedData,
            String rawContent,
            String url,
            Long existingProductId) {
        return processAndSaveProductInternal(brand, extractedData, rawContent, url, existingProductId);
    }

    /**
     * Internal method that does the actual work
     */
    private CoffeeProduct processAndSaveProductInternal(
            CoffeeBrand brand,
            ExtractedProductData extractedData,
            String rawContent,
            String url,
            Long existingProductId) {

        try {
            // Map tasting notes to SCA categories
            SCAFlavorMapping scaMapping = scaService.mapTastingNotes(extractedData.getTastingNotes());

            // Find existing product by ID (if provided) or by URL
            CoffeeProduct product;
            if (existingProductId != null) {
                // Use the provided product ID (most reliable)
                Optional<CoffeeProduct> existingProduct = productRepository.findById(existingProductId);
                if (existingProduct.isPresent()) {
                    product = existingProduct.get();
                    log.info("Updating existing product by ID ({}): {}", product.getId(), product.getProductName());
                } else {
                    log.warn("Product ID {} not found, creating new product", existingProductId);
                    product = CoffeeProduct.builder()
                            .brand(brand)
                            .productName(extractedData.getProductName())
                            .build();
                }
            } else {
                // Fallback to finding by URL (for sitemap crawling)
                Optional<CoffeeProduct> existingProduct = productRepository.findBySellerUrl(url);
                if (existingProduct.isPresent()) {
                    product = existingProduct.get();
                    log.info("Updating existing product by URL (ID: {}): {}", product.getId(), product.getProductName());
                } else {
                    product = CoffeeProduct.builder()
                            .brand(brand)
                            .productName(extractedData.getProductName())
                            .build();
                    log.info("Creating new product: {}", product.getProductName());
                }
            }

            // Update product fields with cleaned/normalized origin
            product.setOrigin(cleanOriginString(extractedData.getOrigin()));
            product.setRegion(extractedData.getRegion());
            product.setProcess(extractedData.getProcess());
            product.setProducer(extractedData.getProducer());
            product.setVariety(extractedData.getVariety());
            product.setAltitude(extractedData.getAltitude());
            product.setPrice(extractedData.getPrice());
            product.setInStock(extractedData.getInStock() != null ? extractedData.getInStock() : true);
            product.setSellerUrl(url);

            // Store price variants as JSON if available
            if (extractedData.getPriceVariants() != null && !extractedData.getPriceVariants().isEmpty()) {
                product.setPriceVariantsJson(objectMapper.writeValueAsString(extractedData.getPriceVariants()));
            }

            // Use raw_description from Perplexity if available, otherwise use provided rawContent
            String description = extractedData.getRawDescription() != null && !extractedData.getRawDescription().isEmpty()
                    ? extractedData.getRawDescription()
                    : rawContent;
            product.setRawDescription(description.length() > 5000 ? description.substring(0, 5000) : description);

            product.setCrawlStatus("done");
            product.setLastUpdateDate(LocalDateTime.now());

            // Serialize tasting notes and SCA mapping to JSON
            product.setTastingNotesJson(objectMapper.writeValueAsString(extractedData.getTastingNotes()));
            product.setScaFlavorsJson(objectMapper.writeValueAsString(scaMapping));

            // Save to database
            product = productRepository.save(product);
            log.info("Saved product to database: {} (ID: {})", product.getProductName(), product.getId());

            // Sync to knowledge graph
            try {
                graphService.syncProductToGraph(product);
            } catch (Exception e) {
                log.error("Failed to sync product to knowledge graph: {}", e.getMessage());
                // Continue even if graph sync fails
            }

            return product;

        } catch (Exception e) {
            log.error("Error processing product data: {}", e.getMessage(), e);

            // Create minimal product record with error status
            CoffeeProduct errorProduct = CoffeeProduct.builder()
                    .brand(brand)
                    .productName(extractedData != null ? extractedData.getProductName() : "Unknown")
                    .crawlStatus("error")
                    .errorMessage(e.getMessage())
                    .lastUpdateDate(LocalDateTime.now())
                    .build();

            return productRepository.save(errorProduct);
        }
    }

    /**
     * Get products that need updating
     */
    public List<CoffeeProduct> getProductsNeedingUpdate() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(updateIntervalDays);
        return productRepository.findProductsNeedingUpdate(cutoffDate);
    }

    /**
     * Retry failed products
     */
    public void retryFailedProducts() {
        List<CoffeeProduct> failedProducts = productRepository.findByCrawlStatus("error");
        log.info("Retrying {} failed products", failedProducts.size());

        for (CoffeeProduct product : failedProducts) {
            if (product.getSellerUrl() != null) {
                try {
                    crawlProduct(product.getBrand(), product.getSellerUrl());
                } catch (Exception e) {
                    log.error("Retry failed for product {}: {}", product.getProductName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Deduplicate URLs to count unique base products (handles roast level variants)
     * Example: "brazil-santos-medium-roast", "brazil-santos-dark-roast" → 1 unique base product
     *
     * @param urls List of product URLs from sitemap
     * @return Count of unique base products after removing roast/grind/size variants
     */
    private int deduplicateRoastVariants(List<String> urls) {
        Set<String> baseProducts = new HashSet<>();

        for (String url : urls) {
            // Extract the base product name by removing roast/grind/size suffixes
            // Example: /products/brazil-santos-medium-roast-coffee-beans → brazil-santos
            String baseName = url
                    .replaceAll(".*/products/", "")  // Remove path prefix
                    .replaceAll("-(light|medium|medium-dark|dark|omni|espresso)(-roast)?", "") // Remove roast level
                    .replaceAll("-(whole-bean|ground|filter|espresso-grind)", "")  // Remove grind type
                    .replaceAll("-(250g|500g|1kg|2kg|5kg)", "")  // Remove size
                    .replaceAll("-coffee-beans?", "")  // Remove "coffee-beans" suffix
                    .replaceAll("-beans?", "")  // Remove "beans" suffix
                    .replaceAll("\\?.*", "");  // Remove query params

            baseProducts.add(baseName);
        }

        log.info("Deduplication: {} URLs → {} unique base products (removed {} variants)",
                urls.size(), baseProducts.size(), urls.size() - baseProducts.size());

        return baseProducts.size();
    }

    /**
     * Clean and normalize origin strings to handle blends and complex formats
     * Examples:
     *   "Blend (Colombia, Brazil, Ethiopia)" → "Blend"
     *   "Blend (50% Costa Rica, 30% Brazil, 20% Nicaragua)" → "Blend"
     *   "Single Origin (varies)" → "Various"
     *   "Colombia" → "Colombia" (unchanged)
     */
    private String cleanOriginString(String origin) {
        if (origin == null || origin.trim().isEmpty()) {
            return null;
        }

        String cleaned = origin.trim();

        // Check if it's a blend with parentheses
        if (cleaned.toLowerCase().startsWith("blend")) {
            return "Blend";
        }

        // Check if it's "Single Origin (varies)" or similar
        if (cleaned.toLowerCase().contains("single origin") && cleaned.toLowerCase().contains("varies")) {
            return "Various";
        }

        // Check if it contains parentheses with multiple countries (blend indicator)
        if (cleaned.contains("(") && cleaned.contains(",")) {
            // Extract just the prefix before parentheses if it's a blend descriptor
            String prefix = cleaned.substring(0, cleaned.indexOf("(")).trim();
            if (prefix.toLowerCase().contains("blend") || prefix.toLowerCase().contains("mixed")) {
                return "Blend";
            }
            // Otherwise keep the original (might be valid format like "Ethiopia (Yirgacheffe)")
        }

        return cleaned;
    }
}
