package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.CrawlSummary;
import com.coffee.beansfinder.dto.ExtractedProductData;
import com.coffee.beansfinder.dto.SCAFlavorMapping;
import com.coffee.beansfinder.dto.SitemapEntry;
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
    private final PriceHistoryService priceHistoryService;
    private final DiscountExtractorService discountExtractorService;

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
     * @param brand The brand to crawl
     * @return CrawlSummary with stats on new/updated/unchanged/deleted products
     */
    // NOTE: No @Transactional here - each product is saved in its own transaction
    // This prevents "idle-in-transaction timeout" during slow OpenAI API calls
    public CrawlSummary crawlBrandFromSitemap(CoffeeBrand brand) {
        return crawlBrandFromSitemap(brand, 0);
    }

    /**
     * Crawl all products from a brand's sitemap using incremental hash-based change detection.
     * Only calls OpenAI for new or changed products, saving API costs.
     *
     * @param brand The brand to crawl
     * @param maxAgeDays Only process products with lastmod within this many days (0 = no filter)
     * @return CrawlSummary with stats on new/updated/unchanged/deleted products
     */
    public CrawlSummary crawlBrandFromSitemap(CoffeeBrand brand, int maxAgeDays) {
        if (maxAgeDays > 0) {
            log.info("Starting incremental sitemap crawl for brand: {} from {} (filtering to last {} days)",
                    brand.getName(), brand.getSitemapUrl(), maxAgeDays);
        } else {
            log.info("Starting incremental sitemap crawl for brand: {} from {}", brand.getName(), brand.getSitemapUrl());
        }

        // Extract discounts from brand homepage
        try {
            int discountsFound = discountExtractorService.extractAndSave(brand);
            log.info("Extracted {} discounts for brand: {}", discountsFound, brand.getName());
        } catch (Exception e) {
            log.warn("Failed to extract discounts for brand {}: {}", brand.getName(), e.getMessage());
        }

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
        int restoredCount = 0;
        int staleDeletedCount = 0;

        try {
            // Step 1: Build lightweight map of existing products (ID + hash only, no entity references)
            // This avoids holding DB connections during slow Playwright operations
            record ProductRef(Long id, String contentHash) {}
            Map<String, ProductRef> existingByUrl = new HashMap<>();
            Set<Long> existingProductIds = new HashSet<>();

            for (CoffeeProduct p : productRepository.findByBrand(brand)) {
                if (p.getSellerUrl() != null) {
                    existingByUrl.put(p.getSellerUrl(), new ProductRef(p.getId(), p.getContentHash()));
                    existingProductIds.add(p.getId());
                }
            }
            log.info("Found {} existing products for brand: {}", existingByUrl.size(), brand.getName());

            // Step 2: Fetch and filter sitemap to coffee products only
            List<String> productUrls;
            if (maxAgeDays > 0) {
                // Use date-filtered method - only products updated within maxAgeDays
                List<SitemapEntry> entries = scraperService.extractProductUrlsFromSitemapWithDateFilter(
                        brand.getSitemapUrl(), maxAgeDays);
                productUrls = entries.stream().map(SitemapEntry::getUrl).toList();
                log.info("Extracted {} URLs from sitemap (filtered by date: last {} days, keyword-based filtering)",
                        productUrls.size(), maxAgeDays);
            } else {
                // Use original method - all coffee products
                productUrls = scraperService.extractProductUrlsFromSitemap(brand.getSitemapUrl());
                log.info("Extracted {} URLs from sitemap (after keyword-based filtering)", productUrls.size());
            }

            if (productUrls.isEmpty()) {
                log.warn("No coffee products found in sitemap for brand: {}", brand.getName());
                return summaryBuilder.totalProcessed(0).build();
            }

            // Track which URLs we've seen in this crawl (for deletion detection)
            // NOTE: When using date filter, we don't delete products not in filtered list
            //       because they may just be older than maxAgeDays
            Set<String> urlsInSitemap = new HashSet<>(productUrls);
            boolean skipDeletion = maxAgeDays > 0;

            // Step 3: Process each URL with hash-based change detection
            int totalUrls = productUrls.size();

            for (int i = 0; i < totalUrls; i++) {
                String productUrl = productUrls.get(i);

                // Step 3a: Playwright extracts clean product text
                log.info("[{}/{}] Extracting text with Playwright: {}", i + 1, totalUrls, productUrl);
                String productText = playwrightService.extractProductText(productUrl);

                // Check if extraction failed or returned too little content
                final int MIN_CONTENT_LENGTH = 300; // Minimum chars for meaningful extraction
                if (productText == null || productText.isEmpty()) {
                    log.error("Playwright failed to extract text for: {}", productUrl);
                    continue; // Skip this URL, don't fail entire crawl
                }

                // If content is too short, try wide extraction (full page body)
                if (productText.length() < MIN_CONTENT_LENGTH) {
                    log.warn("⚠️ Extracted text too short ({} chars < {}), retrying with wide extraction: {}",
                            productText.length(), MIN_CONTENT_LENGTH, productUrl);
                    String wideText = playwrightService.extractProductTextWide(productUrl);
                    if (wideText != null && wideText.length() > productText.length()) {
                        log.info("Wide extraction got {} chars (was {})", wideText.length(), productText.length());
                        productText = wideText;
                    }

                    // If still too short, skip this product
                    if (productText.length() < MIN_CONTENT_LENGTH) {
                        log.error("❌ Content still too short after wide extraction ({} chars), skipping: {}",
                                productText.length(), productUrl);
                        continue;
                    }
                }

                // Step 3b: Generate hash of extracted content
                String newHash = contentHashService.generateHash(productText);

                // Step 3c: Check if product exists (using lightweight ref, not entity)
                ProductRef existingRef = existingByUrl.get(productUrl);

                if (existingRef != null) {
                    // Product exists - check if content changed
                    if (!contentHashService.hasContentChanged(newHash, existingRef.contentHash())) {
                        // Content unchanged - skip OpenAI, save cost!
                        log.info("⏭️ [{}/{}] UNCHANGED (hash match): {}", i + 1, totalUrls, productUrl);
                        unchangedCount++;
                        continue;
                    }

                    // Content changed - need to re-extract with OpenAI
                    log.info("🔄 [{}/{}] CHANGED (hash mismatch): {}", i + 1, totalUrls, productUrl);
                    ExtractedProductData data = extractWithOpenAI(productText, brand.getName(), productUrl);

                    if (data != null) {
                        // Fresh DB lookup for save (opens new connection, saves, closes)
                        processAndSaveProduct(brand, data, productText, productUrl, existingRef.id());
                        // Update hash in separate transaction
                        productRepository.findById(existingRef.id()).ifPresent(p -> {
                            p.setContentHash(newHash);
                            productRepository.save(p);
                        });
                        updatedCount++;
                        log.info("✓ [{}/{}] UPDATED: {} (origin: {})", i + 1, totalUrls,
                                data.getProductName(), data.getOrigin());
                    }
                } else {
                    // Check if this is a soft-deleted product returning (recovery)
                    Optional<CoffeeProduct> softDeleted = productRepository.findBySellerUrlIncludeDeleted(productUrl);

                    if (softDeleted.isPresent() && softDeleted.get().getDeletedAt() != null) {
                        // Product was soft-deleted - restore it!
                        CoffeeProduct restored = softDeleted.get();
                        log.info("♻️ [{}/{}] RESTORING soft-deleted product: {}", i + 1, totalUrls, restored.getProductName());

                        // Clear soft delete flags
                        restored.setDeletedAt(null);
                        try {
                            graphService.restoreProduct(restored.getId());
                        } catch (Exception e) {
                            log.warn("Failed to restore product {} in graph: {}", restored.getId(), e.getMessage());
                        }

                        // Update content if changed
                        if (contentHashService.hasContentChanged(newHash, restored.getContentHash())) {
                            ExtractedProductData data = extractWithOpenAI(productText, brand.getName(), productUrl);
                            if (data != null) {
                                processAndSaveProduct(brand, data, productText, productUrl, restored.getId());
                                restored.setContentHash(newHash);
                            }
                        }

                        productRepository.save(restored);
                        restoredCount++;
                        log.info("✓ [{}/{}] RESTORED: {}", i + 1, totalUrls, restored.getProductName());

                    } else {
                        // Truly new product - extract with OpenAI
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
            }

            // Step 4: Soft delete products no longer in sitemap
            // Skip deletion when using date filter (products may just be older than maxAgeDays)
            if (!skipDeletion) {
                // Find products not in sitemap and soft delete them
                for (Map.Entry<String, ProductRef> entry : existingByUrl.entrySet()) {
                    String url = entry.getKey();
                    ProductRef ref = entry.getValue();
                    if (!urlsInSitemap.contains(url)) {
                        // Fresh DB lookup for deletion
                        productRepository.findById(ref.id()).ifPresent(existing -> {
                            if (existing.getDeletedAt() == null) {
                                log.info("🗑️ SOFT DELETING product not in sitemap: {} (ID: {})",
                                        existing.getProductName(), existing.getId());
                                try {
                                    graphService.markProductAsDeleted(existing.getId());
                                } catch (Exception e) {
                                    log.warn("Failed to mark product {} as deleted in graph: {}", existing.getId(), e.getMessage());
                                }
                                existing.setDeletedAt(LocalDateTime.now());
                                productRepository.save(existing);
                            }
                        });
                        deletedCount++;
                    }
                }
            } else {
                // When using date filter, soft delete products with stale lastmod
                log.info("Checking for stale products (lastmod > {} days)...", maxAgeDays);

                // Get all sitemap entries (without date filter) to find stale products
                List<SitemapEntry> allEntries = scraperService.extractProductUrlsFromSitemapWithDateFilter(
                        brand.getSitemapUrl(), Integer.MAX_VALUE);  // Get all entries with dates

                LocalDateTime cutoffDate = LocalDateTime.now().minusDays(maxAgeDays);

                for (SitemapEntry entry : allEntries) {
                    if (entry.getLastModified() != null && entry.getLastModified().isBefore(cutoffDate)) {
                        // Product is stale - soft delete it
                        ProductRef ref = existingByUrl.get(entry.getUrl());
                        if (ref != null) {
                            // Fresh DB lookup for deletion
                            productRepository.findById(ref.id()).ifPresent(existing -> {
                                if (existing.getDeletedAt() == null) {
                                    log.info("🕐 STALE (lastmod > {} days): {} - soft deleting", maxAgeDays, existing.getProductName());
                                    existing.setDeletedAt(LocalDateTime.now());
                                    productRepository.save(existing);
                                    try {
                                        graphService.markProductAsDeleted(existing.getId());
                                    } catch (Exception e) {
                                        log.warn("Failed to mark stale product {} as deleted in graph: {}", existing.getId(), e.getMessage());
                                    }
                                }
                            });
                            staleDeletedCount++;
                        }
                    }
                }

                if (staleDeletedCount > 0) {
                    log.info("Soft deleted {} stale products (lastmod > {} days)", staleDeletedCount, maxAgeDays);
                }
            }

            // Update brand's last crawl date
            brand.setLastCrawlDate(LocalDateTime.now());
            brandRepository.save(brand);

            // Build summary
            double costSaved = CrawlSummary.calculateCostSaved(unchangedCount);
            int totalDeleted = deletedCount + staleDeletedCount;
            CrawlSummary summary = summaryBuilder
                    .newProducts(newCount)
                    .updatedProducts(updatedCount)
                    .unchangedProducts(unchangedCount)
                    .deletedProducts(totalDeleted)
                    .restoredProducts(restoredCount)
                    .totalProcessed(totalUrls)
                    .apiCostSaved(costSaved)
                    .build();

            log.info("========== CRAWL SUMMARY for {} ==========", brand.getName());
            log.info("  🆕 New products:       {}", newCount);
            log.info("  🔄 Updated products:   {}", updatedCount);
            log.info("  ⏭️ Unchanged products: {}", unchangedCount);
            log.info("  🗑️ Soft deleted:       {} (sitemap: {}, stale: {})", totalDeleted, deletedCount, staleDeletedCount);
            log.info("  ♻️ Restored products:  {}", restoredCount);
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
     * Strategy: Shopify JSON first (fastest, most reliable), then page text, then wide extraction
     */
    private ExtractedProductData extractWithOpenAI(String productText, String brandName, String productUrl) {
        try {
            // STRATEGY 1: Try Shopify JSON endpoint FIRST (most reliable for Shopify stores)
            // The .json endpoint contains the full body_html with origin, process, tasting notes
            if (productUrl.contains("/products/")) {
                log.info("🔍 Trying Shopify JSON endpoint first: {}", productUrl);
                String shopifyJson = playwrightService.fetchShopifyProductJson(productUrl);
                if (shopifyJson != null && !shopifyJson.isEmpty() && shopifyJson.length() > 100) {
                    // Combine page text with Shopify JSON for comprehensive extraction
                    String combinedText = productText + "\n\nSHOPIFY_PRODUCT_DATA:\n" + shopifyJson;
                    ExtractedProductData jsonData = openAIService.extractFromText(combinedText, brandName, productUrl);

                    if (jsonData != null && jsonData.getProductName() != null) {
                        boolean jsonHasOrigin = !isEmptyOrNull(jsonData.getOrigin());
                        boolean jsonHasTastingNotes = jsonData.getTastingNotes() != null && !jsonData.getTastingNotes().isEmpty();

                        if (jsonHasOrigin && jsonHasTastingNotes) {
                            log.info("✓ Shopify JSON extraction complete: origin={}, process={}, tastingNotes={}",
                                    jsonData.getOrigin(), jsonData.getProcess(),
                                    jsonData.getTastingNotes() != null ? jsonData.getTastingNotes().size() : 0);
                            if (!isValidCoffeeProduct(jsonData)) {
                                log.warn("Skipping non-coffee product: {} - {}", jsonData.getProductName(), productUrl);
                                return null;
                            }
                            return jsonData;
                        }
                        // Partial success - continue with other methods
                        log.info("Shopify JSON partial: origin={}, tastingNotes={}", jsonHasOrigin, jsonHasTastingNotes);
                    }
                }
            }

            // STRATEGY 2: Use the targeted page extraction text
            ExtractedProductData data = openAIService.extractFromText(productText, brandName, productUrl);

            if (data == null || data.getProductName() == null) {
                log.error("OpenAI failed to extract data for: {}", productUrl);
                return null;
            }

            // Check if we have complete data
            boolean hasTastingNotes = data.getTastingNotes() != null && !data.getTastingNotes().isEmpty();
            boolean hasOrigin = !isEmptyOrNull(data.getOrigin());

            if (hasTastingNotes && hasOrigin) {
                // Success - have both tasting notes and origin
                if (!isValidCoffeeProduct(data)) {
                    log.warn("Skipping non-coffee product (missing origin/process/variety): {} - {}",
                            data.getProductName(), productUrl);
                    return null;
                }
                return data;
            }

            // STRATEGY 3: No tasting notes - try WIDER extraction (full page body text)
            if (!hasTastingNotes) {
                log.warn("⚠️ No tasting notes found for {} - trying WIDE extraction (full page + JSON-LD + meta tags)...",
                        productUrl);

                // Use wide extraction that captures meta tags, JSON-LD, and full body
                String wideText = playwrightService.extractProductTextWide(productUrl);
                if (wideText != null && !wideText.isEmpty() && wideText.length() > productText.length()) {
                    log.info("Wide extraction got more content ({} chars vs {} chars), re-extracting...",
                            wideText.length(), productText.length());

                    ExtractedProductData wideData = openAIService.extractFromText(wideText, brandName, productUrl);

                    if (wideData != null && wideData.getProductName() != null) {
                        boolean wideHasTastingNotes = wideData.getTastingNotes() != null && !wideData.getTastingNotes().isEmpty();

                        if (wideHasTastingNotes) {
                            // Wide extraction found tasting notes - validate before returning
                            if (!isValidCoffeeProduct(wideData)) {
                                log.warn("Skipping non-coffee product (missing origin/process/variety): {} - {}",
                                        wideData.getProductName(), productUrl);
                                return null;
                            }
                            log.info("✓ Wide extraction found tasting notes: {}", wideData.getTastingNotes());
                            return wideData;
                        }
                    }
                }
            }

            // Still no tasting notes - validate before returning
            // If missing 2+ of origin/process/variety, it's likely not a coffee bean product
            if (!isValidCoffeeProduct(data)) {
                log.warn("Skipping non-coffee product (missing origin/process/variety): {} - {}",
                        data.getProductName(), productUrl);
                return null;
            }

            log.warn("⚠️ No tasting notes found after all fallbacks for: {} - saving anyway", productUrl);
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

            // Validate: if missing 2+ of origin/process/variety, it's likely not a coffee bean product
            if (!isValidCoffeeProduct(extractedData)) {
                log.warn("Skipping non-coffee product (missing origin/process/variety): {} - {}",
                        extractedData.getProductName(), productUrl);
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
     * Save extracted products in batch.
     * No @Transactional - each productRepository.save() auto-commits immediately.
     */
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
     * Process extracted data and save to database.
     * No @Transactional - each productRepository.save() auto-commits immediately,
     * preventing connection timeouts during long crawl operations.
     */
    public CoffeeProduct processAndSaveProduct(
            CoffeeBrand brand,
            ExtractedProductData extractedData,
            String rawContent,
            String url) {
        return processAndSaveProductInternal(brand, extractedData, rawContent, url, null);
    }

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
            // Set currency from extraction (defaults to GBP if not extracted)
            if (extractedData.getCurrency() != null && !extractedData.getCurrency().isEmpty()) {
                product.setCurrency(extractedData.getCurrency());
            }
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

            // Set description summary (condensed paraphrase for copyright compliance)
            if (extractedData.getDescriptionSummary() != null && !extractedData.getDescriptionSummary().isEmpty()) {
                product.setDescriptionSummary(extractedData.getDescriptionSummary());
            }

            product.setCrawlStatus("done");
            product.setLastUpdateDate(LocalDateTime.now());

            // Serialize tasting notes and SCA mapping to JSON
            product.setTastingNotesJson(objectMapper.writeValueAsString(extractedData.getTastingNotes()));
            product.setScaFlavorsJson(objectMapper.writeValueAsString(scaMapping));

            // Store flavor profile and character axes (13-dimensional vector system)
            if (extractedData.getFlavorProfile() != null && extractedData.getFlavorProfile().size() == 9) {
                product.setFlavorProfileJson(objectMapper.writeValueAsString(extractedData.getFlavorProfile()));
            } else {
                // Fallback: generate from SCA mapping using count-based intensity
                List<Double> generatedProfile = generateFlavorProfileFromSCA(scaMapping);
                product.setFlavorProfileJson(objectMapper.writeValueAsString(generatedProfile));
            }

            if (extractedData.getCharacterAxes() != null && extractedData.getCharacterAxes().size() == 4) {
                product.setCharacterAxesJson(objectMapper.writeValueAsString(extractedData.getCharacterAxes()));
            } else {
                // Fallback: generate neutral character axes
                List<Double> neutralAxes = List.of(0.0, 0.0, 0.0, 0.0);
                product.setCharacterAxesJson(objectMapper.writeValueAsString(neutralAxes));
            }

            // Save to database
            product = productRepository.save(product);
            log.info("Saved product to database: {} (ID: {})", product.getProductName(), product.getId());

            // Record price history (for price tracking and trend analysis)
            try {
                priceHistoryService.recordPrice(product);
            } catch (Exception e) {
                log.warn("Failed to record price history for product {}: {}", product.getId(), e.getMessage());
                // Continue even if price history fails
            }

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
     * Validate if extracted data represents a real coffee bean product.
     * If missing 2+ of origin/process/variety AND has no tasting notes, it's likely equipment/courses.
     *
     * Blends are allowed if they have tasting notes (even without origin/process/variety).
     *
     * Examples of non-coffee products that slip through:
     * - "Espresso Masterclass" (course) - no origin, no process, no variety, no tasting notes
     * - "CAFEC TH-3 Paper Filter" (equipment) - no origin, no process, no variety, no tasting notes
     * - "Filter selection" (subscription box) - no origin, no process, no variety, no tasting notes
     *
     * Examples of valid blends:
     * - "Firehouse Blend" - no origin but has tasting notes (chocolate, forest fruits, etc.)
     */
    private boolean isValidCoffeeProduct(ExtractedProductData data) {
        // If product has tasting notes, it's likely a real coffee (including blends)
        boolean hasTastingNotes = data.getTastingNotes() != null && !data.getTastingNotes().isEmpty();
        if (hasTastingNotes) {
            return true;
        }

        // No tasting notes - check origin/process/variety
        int missingCount = 0;

        // Check origin
        if (isEmptyOrNull(data.getOrigin())) {
            missingCount++;
        }

        // Check process
        if (isEmptyOrNull(data.getProcess())) {
            missingCount++;
        }

        // Check variety
        if (isEmptyOrNull(data.getVariety())) {
            missingCount++;
        }

        // If missing 2 or more AND no tasting notes, it's likely not a coffee bean product
        return missingCount < 2;
    }

    private boolean isEmptyOrNull(String value) {
        return value == null || value.trim().isEmpty() || value.equalsIgnoreCase("N/A");
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

    /**
     * Generate a 9-dimensional flavor profile from SCA mapping.
     * Uses count-based intensity: 0 notes = 0.0, 1 note = 0.4, 2 notes = 0.6, 3+ notes = 0.8
     * Order: [fruity, floral, sweet, nutty, spices, roasted, green, sour, other]
     *
     * @param mapping SCA flavor mapping with categorized tasting notes
     * @return List of 9 intensity values [0.0-1.0]
     */
    public List<Double> generateFlavorProfileFromSCA(SCAFlavorMapping mapping) {
        if (mapping == null) {
            return List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
        return List.of(
                calculateIntensity(mapping.getFruity()),
                calculateIntensity(mapping.getFloral()),
                calculateIntensity(mapping.getSweet()),
                calculateIntensity(mapping.getNutty()),
                calculateIntensity(mapping.getSpices()),
                calculateIntensity(mapping.getRoasted()),
                calculateIntensity(mapping.getGreen()),
                calculateIntensity(mapping.getSour()),
                calculateIntensity(mapping.getOther())
        );
    }

    /**
     * Calculate intensity based on note count.
     * 0 notes = 0.0 (not present)
     * 1 note = 0.4 (noticeable)
     * 2 notes = 0.6 (prominent)
     * 3+ notes = 0.8 (defining)
     */
    private double calculateIntensity(List<String> notes) {
        if (notes == null || notes.isEmpty()) return 0.0;
        int count = notes.size();
        if (count == 1) return 0.4;
        if (count == 2) return 0.6;
        return 0.8;
    }
}
