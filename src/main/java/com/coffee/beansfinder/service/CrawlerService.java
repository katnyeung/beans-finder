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
import java.util.List;
import java.util.Optional;

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
    private final ObjectMapper objectMapper;

    @Value("${crawler.update.interval.days:14}")
    private int updateIntervalDays;

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

        // Check robots.txt if required
        if (brand.getRespectRobotsTxt() && brand.getRobotsTxtUrl() != null) {
            if (!scraperService.isAllowedByRobotsTxt(brand.getWebsite(), brand.getRobotsTxtUrl())) {
                log.warn("Brand {} disallowed by robots.txt", brand.getName());
                return;
            }
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

            // Extract content
            String rawContent = scraperService.extractTextContent(doc.get());
            WebScraperService.ProductPageMetadata metadata = scraperService.extractMetadata(doc.get());

            // Use Perplexity to extract structured data
            ExtractedProductData extractedData = perplexityService.extractProductData(
                    rawContent + "\n\nTitle: " + metadata.title + "\n\nDescription: " + metadata.description,
                    brand.getName(),
                    productUrl
            );

            // Process and save
            return processAndSaveProduct(brand, extractedData, rawContent, productUrl);

        } catch (Exception e) {
            log.error("Error crawling product {}: {}", productUrl, e.getMessage(), e);
            return null;
        }
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
            product.setRawDescription(rawContent.length() > 5000 ? rawContent.substring(0, 5000) : rawContent);
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
}
