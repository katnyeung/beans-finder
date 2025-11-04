package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.service.CoffeeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for coffee product operations
 */
@RestController
@RequestMapping("/api/coffee")
@RequiredArgsConstructor
@Slf4j
public class CoffeeController {

    private final CoffeeService coffeeService;

    /**
     * Create or update a coffee product
     * POST /api/coffee
     */
    @PostMapping
    public ResponseEntity<CoffeeProduct> createProduct(@RequestBody Map<String, String> request) {
        String brand = request.get("brand");
        String productName = request.get("productName");
        String url = request.get("url");

        if (brand == null || productName == null) {
            return ResponseEntity.badRequest().build();
        }

        try {
            CoffeeProduct product = coffeeService.createOrUpdateProduct(brand, productName, url);
            return ResponseEntity.ok(product);
        } catch (Exception e) {
            log.error("Error creating product", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get all products
     * GET /api/coffee
     */
    @GetMapping
    public ResponseEntity<List<CoffeeProduct>> getAllProducts() {
        return ResponseEntity.ok(coffeeService.searchProducts(null, null, null));
    }

    /**
     * Get products by brand
     * GET /api/coffee/brand/{brand}
     */
    @GetMapping("/brand/{brand}")
    public ResponseEntity<List<CoffeeProduct>> getProductsByBrand(@PathVariable String brand) {
        return ResponseEntity.ok(coffeeService.getProductsByBrand(brand));
    }

    /**
     * Get in-stock products
     * GET /api/coffee/in-stock
     */
    @GetMapping("/in-stock")
    public ResponseEntity<List<CoffeeProduct>> getInStockProducts() {
        return ResponseEntity.ok(coffeeService.getInStockProducts());
    }

    /**
     * Search products by criteria
     * GET /api/coffee/search?origin=Colombia&process=Honey&variety=Geisha
     */
    @GetMapping("/search")
    public ResponseEntity<List<CoffeeProduct>> searchProducts(
        @RequestParam(required = false) String origin,
        @RequestParam(required = false) String process,
        @RequestParam(required = false) String variety
    ) {
        return ResponseEntity.ok(coffeeService.searchProducts(origin, process, variety));
    }

    /**
     * Get products needing update
     * GET /api/coffee/outdated?days=14
     */
    @GetMapping("/outdated")
    public ResponseEntity<List<CoffeeProduct>> getOutdatedProducts(
        @RequestParam(defaultValue = "14") int days
    ) {
        return ResponseEntity.ok(coffeeService.getProductsNeedingUpdate(days));
    }
}
