package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.ExtractionResponse;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Main service for coffee product operations
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoffeeService {

    private final CoffeeProductRepository productRepository;
    private final PerplexityApiClient perplexityClient;
    private final WebScraperService scraperService;
    private final SCAFlavorWheelMapper flavorMapper;
    private final KnowledgeGraphService graphService;

    /**
     * Create or update a coffee product with extracted data
     */
    @Transactional
    public CoffeeProduct createOrUpdateProduct(String brand, String productName, String url) {
        log.info("Creating/updating product: {} - {}", brand, productName);

        try {
            // Check if product already exists
            Optional<CoffeeProduct> existing = productRepository.findByBrandAndProductName(brand, productName);

            CoffeeProduct product;
            if (existing.isPresent()) {
                product = existing.get();
                product.setCrawlStatus("in_progress");
                productRepository.save(product);
            } else {
                product = CoffeeProduct.builder()
                    .brand(brand)
                    .productName(productName)
                    .sellerUrl(url)
                    .crawlStatus("in_progress")
                    .build();
                product = productRepository.save(product);
            }

            // Fetch and extract data
            ExtractionResponse extracted = extractProductData(brand, productName, url);

            // Update product with extracted data
            updateProductFromExtraction(product, extracted);
            product.setCrawlStatus("completed");
            product.setLastUpdateDate(LocalDateTime.now());

            product = productRepository.save(product);

            // Update knowledge graph
            graphService.createOrUpdateProductNode(product);

            log.info("Successfully created/updated product: {}", product.getId());
            return product;

        } catch (Exception e) {
            log.error("Error creating/updating product: {} - {}", brand, productName, e);

            // Mark as failed
            CoffeeProduct failedProduct = productRepository.findByBrandAndProductName(brand, productName)
                .orElse(CoffeeProduct.builder()
                    .brand(brand)
                    .productName(productName)
                    .sellerUrl(url)
                    .build());

            failedProduct.setCrawlStatus("failed");
            return productRepository.save(failedProduct);
        }
    }

    /**
     * Extract product data using Perplexity API
     */
    private ExtractionResponse extractProductData(String brand, String productName, String url) throws Exception {
        if (url != null && !url.isEmpty()) {
            // Scrape the URL
            String html = scraperService.fetchHtmlContent(url);
            String content = scraperService.extractMainContent(html);

            // Extract using Perplexity
            return perplexityClient.extractCoffeeData(brand, productName, content);
        } else {
            // Search and extract
            return perplexityClient.searchAndExtract(brand, productName);
        }
    }

    /**
     * Update product entity from extraction response
     */
    private void updateProductFromExtraction(CoffeeProduct product, ExtractionResponse extracted) {
        if (extracted.getProductName() != null) {
            product.setProductName(extracted.getProductName());
        }
        if (extracted.getOrigin() != null) {
            product.setOrigin(extracted.getOrigin());
        }
        if (extracted.getRegion() != null) {
            product.setRegion(extracted.getRegion());
        }
        if (extracted.getProcess() != null) {
            product.setProcess(extracted.getProcess());
        }
        if (extracted.getProducer() != null) {
            product.setProducer(extracted.getProducer());
        }
        if (extracted.getVariety() != null) {
            product.setVariety(extracted.getVariety());
        }
        if (extracted.getAltitude() != null) {
            product.setAltitude(extracted.getAltitude());
        }
        if (extracted.getTastingNotes() != null && !extracted.getTastingNotes().isEmpty()) {
            product.setTastingNotes(extracted.getTastingNotes());

            // Map to SCA flavors
            var scaMapping = flavorMapper.mapTastingNotes(extracted.getTastingNotes());
            product.setScaFlavors(scaMapping);
        }
        if (extracted.getPrice() != null) {
            product.setPrice(extracted.getPrice());
        }
        if (extracted.getInStock() != null) {
            product.setInStock(extracted.getInStock());
        }
    }

    /**
     * Get products that need updating (older than configured interval)
     */
    public List<CoffeeProduct> getProductsNeedingUpdate(int daysOld) {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysOld);
        return productRepository.findProductsNeedingUpdate(cutoffDate);
    }

    /**
     * Get all products by brand
     */
    public List<CoffeeProduct> getProductsByBrand(String brand) {
        return productRepository.findByBrand(brand);
    }

    /**
     * Search products by various criteria
     */
    public List<CoffeeProduct> searchProducts(String origin, String process, String variety) {
        if (origin != null) {
            return productRepository.findByOrigin(origin);
        } else if (process != null) {
            return productRepository.findByProcess(process);
        } else if (variety != null) {
            return productRepository.findByVariety(variety);
        }
        return productRepository.findAll();
    }

    /**
     * Get all in-stock products
     */
    public List<CoffeeProduct> getInStockProducts() {
        return productRepository.findInStockProducts();
    }
}
