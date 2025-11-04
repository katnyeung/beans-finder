package com.coffee.beansfinder.controller;

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
 * REST API for managing coffee products
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Products", description = "Coffee product management and queries")
public class ProductController {

    private final CoffeeProductRepository productRepository;
    private final CoffeeBrandRepository brandRepository;
    private final CrawlerService crawlerService;

    /**
     * Get all products
     */
    @GetMapping
    public List<CoffeeProduct> getAllProducts() {
        return productRepository.findAll();
    }

    /**
     * Get product by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<CoffeeProduct> getProductById(@PathVariable Long id) {
        return productRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get products by brand
     */
    @GetMapping("/brand/{brandId}")
    public List<CoffeeProduct> getProductsByBrand(@PathVariable Long brandId) {
        return productRepository.findByBrandId(brandId);
    }

    /**
     * Get products by origin
     */
    @GetMapping("/origin/{origin}")
    public List<CoffeeProduct> getProductsByOrigin(@PathVariable String origin) {
        return productRepository.findByOrigin(origin);
    }

    /**
     * Get products by process
     */
    @GetMapping("/process/{process}")
    public List<CoffeeProduct> getProductsByProcess(@PathVariable String process) {
        return productRepository.findByProcess(process);
    }

    /**
     * Get products by variety
     */
    @GetMapping("/variety/{variety}")
    public List<CoffeeProduct> getProductsByVariety(@PathVariable String variety) {
        return productRepository.findByVariety(variety);
    }

    /**
     * Get in-stock products
     */
    @GetMapping("/in-stock")
    public List<CoffeeProduct> getInStockProducts() {
        return productRepository.findByInStockTrue();
    }

    /**
     * Manually crawl a specific product
     */
    @PostMapping("/crawl")
    public ResponseEntity<CoffeeProduct> crawlProduct(@RequestBody CrawlProductRequest request) {
        try {
            CoffeeBrand brand = brandRepository.findById(request.brandId)
                    .orElseThrow(() -> new IllegalArgumentException("Brand not found"));

            CoffeeProduct product = crawlerService.crawlProduct(brand, request.productUrl);

            if (product != null) {
                return ResponseEntity.ok(product);
            } else {
                return ResponseEntity.badRequest().build();
            }
        } catch (Exception e) {
            log.error("Failed to crawl product: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    // DTOs
    public record CrawlProductRequest(
            Long brandId,
            String productUrl
    ) {}
}
