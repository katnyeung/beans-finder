package com.coffee.beansfinder.service;

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
import java.util.HashSet;
import java.util.List;
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
    @Transactional
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
    @Transactional
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
     * Crawl all products from a brand's sitemap using Perplexity batch extraction
     */
    @Transactional
    public void crawlBrandFromSitemap(CoffeeBrand brand) {
        log.info("Starting sitemap crawl for brand: {} from {}", brand.getName(), brand.getSitemapUrl());

        if (brand.getSitemapUrl() == null || brand.getSitemapUrl().isEmpty()) {
            log.error("Brand {} has no sitemap URL", brand.getName());
            return;
        }

        try {
            // Step 1: Clean up existing products for this brand
            List<CoffeeProduct> existingProducts = productRepository.findByBrand(brand);
            if (!existingProducts.isEmpty()) {
                log.info("Cleaning up {} existing products for brand: {}", existingProducts.size(), brand.getName());

                // Delete from Neo4j knowledge graph first
                for (CoffeeProduct product : existingProducts) {
                    try {
                        graphService.deleteProductFromGraph(product.getId());
                    } catch (Exception e) {
                        log.warn("Failed to delete product {} from knowledge graph: {}",
                                product.getId(), e.getMessage());
                    }
                }

                // Delete from PostgreSQL
                productRepository.deleteAll(existingProducts);
                log.info("Successfully cleaned up {} products", existingProducts.size());
            }

            // Step 2: Fetch and filter sitemap to coffee products only
            List<String> productUrls = scraperService.extractProductUrlsFromSitemap(brand.getSitemapUrl());
            log.info("Extracted {} URLs from sitemap (after sitemap filtering)", productUrls.size());

            if (productUrls.isEmpty()) {
                log.warn("No coffee products found in sitemap for brand: {}", brand.getName());
                return;
            }

            // Step 2.5: Use Perplexity to further filter URLs (AI-based classification)
            List<String> filteredUrls = perplexityService.filterCoffeeBeanUrls(productUrls, brand.getName());
            log.info("After Perplexity AI filtering: {} coffee bean URLs (removed {} non-coffee items)",
                    filteredUrls.size(), productUrls.size() - filteredUrls.size());

            if (filteredUrls.isEmpty()) {
                log.warn("No coffee bean products found after Perplexity filtering for brand: {}", brand.getName());
                return;
            }

            // Use filtered URLs for extraction
            productUrls = filteredUrls;

            // Step 3: Try Perplexity batch extraction first (faster and cheaper)
            log.info("Sending {} URLs to Perplexity for batch extraction", productUrls.size());
            List<ExtractedProductData> extractedProducts = perplexityService.extractProductsFromUrls(
                    brand.getName(),
                    productUrls
            );

            log.info("Perplexity extracted {} products from {} URLs",
                     extractedProducts.size(), productUrls.size());

            // Step 4: Check if Perplexity failed (mostly empty results OR too few products)
            int emptyCount = 0;
            for (ExtractedProductData data : extractedProducts) {
                if (isMostlyEmpty(data)) {
                    emptyCount++;
                }
            }

            double emptyPercentage = extractedProducts.isEmpty() ? 100.0 :
                    (emptyCount * 100.0 / extractedProducts.size());

            // Calculate extraction rate (how many products vs URLs)
            // IMPORTANT: Deduplicate URLs to handle roast variants before calculating rate
            // Example: "brazil-santos-medium", "brazil-santos-dark" → count as 1 base product
            int uniqueBaseProducts = deduplicateRoastVariants(productUrls);
            double extractionRate = (extractedProducts.size() * 100.0 / uniqueBaseProducts);

            log.info("Extraction rate: {} products from {} unique base products ({} total URLs) = {:.1f}%",
                    extractedProducts.size(), uniqueBaseProducts, productUrls.size(), extractionRate);

            // Trigger Playwright fallback if:
            // 1. >70% of extracted products are empty/incomplete, OR
            // 2. Extracted <50% of expected products (e.g., got 1 out of 30 unique base products)
            // 3. AND total URLs < 100 (safety: don't fallback for 1000+ URL sites)
            boolean shouldFallback = (emptyPercentage > 70 || extractionRate < 50) && productUrls.size() < 100;

            if (shouldFallback) {
                String reason = emptyPercentage > 70
                        ? String.format("%.1f%% empty results (%d/%d)", emptyPercentage, emptyCount, extractedProducts.size())
                        : String.format("only extracted %.1f%% of products (%d/%d)", extractionRate, extractedProducts.size(), productUrls.size());

                log.warn("Perplexity batch failed: {}. Using Playwright + OpenAI fallback (20x cheaper!)", reason);

                extractedProducts.clear(); // Clear failed results

                int totalUrls = productUrls.size();
                int processedCount = 0;
                int chunkSize = playwrightChunkSize; // Configurable chunk size (default: 10)

                for (int i = 0; i < totalUrls; i++) {
                    String productUrl = productUrls.get(i);

                    // Step 1: Playwright renders and extracts product text (not full HTML)
                    // This reduces token usage by 90% (10KB vs 100KB)
                    log.info("[{}/{}] Extracting product text with Playwright: {}", i + 1, totalUrls, productUrl);
                    String productText = playwrightService.extractProductText(productUrl);

                    if (productText == null || productText.isEmpty()) {
                        log.error("Playwright failed to extract text for: {}", productUrl);
                        throw new RuntimeException("Playwright extraction failed for: " + productUrl + ". Stopping crawl.");
                    }

                    // Step 2: Send clean product text to OpenAI for AI-powered extraction
                    // Cost: ~$0.0004 per product (20x cheaper than Perplexity!)
                    log.info("Extracting data from product text with OpenAI ({} chars)...", productText.length());
                    ExtractedProductData data = openAIService.extractFromText(
                            productText,
                            brand.getName(),
                            productUrl
                    );

                    if (data == null || data.getProductName() == null) {
                        log.error("OpenAI failed to extract data for: {}", productUrl);
                        throw new RuntimeException("OpenAI extraction failed for: " + productUrl + ". Stopping crawl.");
                    }

                    extractedProducts.add(data);
                    processedCount++;
                    log.info("✓ [{}/{}] OpenAI extracted: {} (origin: {}, process: {}, notes: {})",
                            i + 1, totalUrls,
                            data.getProductName(),
                            data.getOrigin(),
                            data.getProcess(),
                            data.getTastingNotes() != null ? data.getTastingNotes().size() : 0);

                    // Save in chunks to prevent connection timeout
                    if (processedCount % chunkSize == 0 || i == totalUrls - 1) {
                        log.info("Saving chunk of {} products to database...", extractedProducts.size());
                        saveExtractedProducts(brand, extractedProducts);
                        extractedProducts.clear(); // Clear saved products
                        log.info("Chunk saved successfully. Continuing extraction...");
                    }
                }

                log.info("Playwright + OpenAI fallback completed. Processed {} URLs",
                         totalUrls);

                // Update brand's last crawl date
                brand.setLastCrawlDate(LocalDateTime.now());
                brandRepository.save(brand);

                // Skip the regular save loop below since we already saved in chunks
                return;
            }

            // Step 5: Save all extracted products to database
            int successCount = 0;
            int errorCount = 0;

            for (ExtractedProductData productData : extractedProducts) {
                try {
                    log.info("Saving product: {}", productData.getProductName());

                    // Use product URL from extracted data if available, otherwise fallback to brand website
                    String productUrl = productData.getProductUrl() != null && !productData.getProductUrl().isEmpty()
                            ? productData.getProductUrl()
                            : brand.getWebsite();

                    CoffeeProduct product = processAndSaveProduct(
                            brand,
                            productData,
                            "Extracted from sitemap via Perplexity batch",
                            productUrl
                    );

                    if (product != null && !"error".equals(product.getCrawlStatus())) {
                        successCount++;
                        log.info("Successfully saved product: {} (ID: {}) with URL: {}",
                                 product.getProductName(), product.getId(), productUrl);
                    } else {
                        errorCount++;
                        log.warn("Failed to save product: {}", productData.getProductName());
                    }

                } catch (Exception e) {
                    errorCount++;
                    log.error("Error saving product {}: {}", productData.getProductName(), e.getMessage());
                }
            }

            // Update brand's last crawl date
            brand.setLastCrawlDate(LocalDateTime.now());
            brandRepository.save(brand);

            log.info("Sitemap crawl completed for brand: {}. Success: {}, Errors: {}",
                     brand.getName(), successCount, errorCount);

        } catch (Exception e) {
            log.error("Error crawling sitemap for brand {}: {}", brand.getName(), e.getMessage(), e);
        }
    }

    /**
     * Crawl a specific product URL
     */
    @Transactional
    public CoffeeProduct crawlProduct(CoffeeBrand brand, String productUrl) {
        log.info("Crawling product: {} from brand: {}", productUrl, brand.getName());

        try {
            // Fetch product page
            Optional<Document> doc = scraperService.fetchPage(productUrl);

            if (doc.isEmpty()) {
                log.error("Failed to fetch product page: {}", productUrl);
                return null;
            }

            String htmlContent = doc.get().html();

            // Check if this is a JavaScript-rendered site
            if (playwrightService.isJavaScriptRendered(htmlContent)) {
                log.info("Detected JavaScript-rendered site, using Playwright: {}", productUrl);
                ExtractedProductData playwrightData = playwrightService.extractProductData(productUrl);

                if (playwrightData != null && playwrightData.getProductName() != null) {
                    log.info("Successfully extracted with Playwright: {}", playwrightData.getProductName());
                    return processAndSaveProduct(brand, playwrightData,
                            playwrightData.getRawDescription() != null ? playwrightData.getRawDescription() : "",
                            productUrl);
                } else {
                    log.warn("Playwright extraction failed, falling back to Perplexity");
                }
            }

            // Extract content using traditional scraping
            String rawContent = scraperService.extractTextContent(doc.get());
            WebScraperService.ProductPageMetadata metadata = scraperService.extractMetadata(doc.get());

            // Use Perplexity to extract structured data
            ExtractedProductData extractedData = perplexityService.extractProductData(
                    rawContent + "\n\nTitle: " + metadata.title + "\n\nDescription: " + metadata.description,
                    brand.getName(),
                    productUrl
            );

            // Check if Perplexity returned mostly empty data (sign of JavaScript rendering)
            if (isMostlyEmpty(extractedData)) {
                log.warn("Perplexity returned mostly empty data, trying Playwright fallback");
                ExtractedProductData playwrightData = playwrightService.extractProductData(productUrl);

                if (playwrightData != null && playwrightData.getProductName() != null) {
                    log.info("Playwright fallback successful: {}", playwrightData.getProductName());
                    extractedData = playwrightData;
                }
            }

            // Process and save
            return processAndSaveProduct(brand, extractedData, rawContent, productUrl);

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
     */
    @Transactional
    protected CoffeeProduct processAndSaveProduct(
            CoffeeBrand brand,
            ExtractedProductData extractedData,
            String rawContent,
            String url) {

        try {
            // Map tasting notes to SCA categories
            SCAFlavorMapping scaMapping = scaService.mapTastingNotes(extractedData.getTastingNotes());

            // Find existing product or create new
            Optional<CoffeeProduct> existingProduct = productRepository.findByBrandAndProductName(
                    brand,
                    extractedData.getProductName()
            );

            CoffeeProduct product;
            if (existingProduct.isPresent()) {
                product = existingProduct.get();
                log.info("Updating existing product: {}", product.getProductName());
            } else {
                product = CoffeeProduct.builder()
                        .brand(brand)
                        .productName(extractedData.getProductName())
                        .build();
                log.info("Creating new product: {}", product.getProductName());
            }

            // Update product fields
            product.setOrigin(extractedData.getOrigin());
            product.setRegion(extractedData.getRegion());
            product.setProcess(extractedData.getProcess());
            product.setProducer(extractedData.getProducer());
            product.setVariety(extractedData.getVariety());
            product.setAltitude(extractedData.getAltitude());
            product.setPrice(extractedData.getPrice());
            product.setInStock(extractedData.getInStock() != null ? extractedData.getInStock() : true);
            product.setSellerUrl(url);

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
}
