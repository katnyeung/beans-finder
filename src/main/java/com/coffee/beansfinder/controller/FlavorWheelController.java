package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.dto.FlavorCountDTO;
import com.coffee.beansfinder.graph.node.ProductNode;
import com.coffee.beansfinder.graph.repository.ProductNodeRepository;
import com.coffee.beansfinder.graph.repository.SCACategoryRepository;
import com.coffee.beansfinder.graph.repository.TastingNoteNodeRepository;
import com.coffee.beansfinder.service.SCAFlavorWheelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/flavor-wheel")
@Tag(name = "Flavor Wheel", description = "Interactive SCA flavor wheel endpoints with WCR Sensory Lexicon subcategories")
public class FlavorWheelController {

    @Autowired
    private ProductNodeRepository productNodeRepository;

    @Autowired
    private TastingNoteNodeRepository tastingNoteNodeRepository;

    @Autowired
    private SCACategoryRepository scaCategoryRepository;

    @Autowired
    private SCAFlavorWheelService scaFlavorWheelService;

    @GetMapping("/data")
    @Operation(summary = "Get complete flavor wheel hierarchy",
               description = "Returns all SCA categories with their flavors and product counts for visualization (served from static cache)")
    public ResponseEntity<Map<String, Object>> getFlavorWheelData() {
        // NOTE: This endpoint now uses static cache file (flavor-wheel-data.json)
        // Frontend should load from /cache/flavor-wheel-data.json directly (zero Neo4j queries)
        // This fallback is kept for API compatibility

        // Single efficient query - returns raw map data from TastingNoteNode 4-tier hierarchy
        List<Map<String, Object>> allFlavorData = tastingNoteNodeRepository.findAllTastingNotesWithProductCountsAsMap();

        System.out.println("=== FLAVOR WHEEL DATA DEBUG ===");
        System.out.println("Total rows returned: " + allFlavorData.size());
        if (!allFlavorData.isEmpty()) {
            System.out.println("First row keys: " + allFlavorData.get(0).keySet());
            System.out.println("First row: " + allFlavorData.get(0));
        }

        // Group by category
        Map<String, List<Map<String, Object>>> categoryMap = new HashMap<>();
        Map<String, Integer> categoryCounts = new HashMap<>();

        int skippedRows = 0;
        int processedRows = 0;

        for (Map<String, Object> row : allFlavorData) {
            // Spring Data Neo4j auto-unwraps single-column results
            // So {data: {...}} becomes {...} automatically
            String category = (String) row.get("category");
            String flavorName = (String) row.get("flavorName");
            Object productCountObj = row.get("productCount");

            // Handle both Long and Integer types
            Long productCount = null;
            if (productCountObj instanceof Long) {
                productCount = (Long) productCountObj;
            } else if (productCountObj instanceof Integer) {
                productCount = ((Integer) productCountObj).longValue();
            }

            // Skip if missing data
            if (flavorName == null || productCount == null || productCount == 0) {
                skippedRows++;
                System.out.println("Skipping row: flavorName=" + flavorName + ", category=" + category + ", productCount=" + productCount);
                continue;
            }
            processedRows++;

            // Ensure category is never null (defensive programming)
            if (category == null || category.isEmpty()) {
                category = "other";
            }

            categoryMap.putIfAbsent(category, new ArrayList<>());

            Map<String, Object> flavor = new HashMap<>();
            flavor.put("name", flavorName);
            flavor.put("productCount", productCount.intValue());

            categoryMap.get(category).add(flavor);
            categoryCounts.merge(category, productCount.intValue(), Integer::sum);
        }

        // Build final categories list
        List<Map<String, Object>> categories = new ArrayList<>();
        int totalProducts = 0;
        int totalFlavors = 0;

        for (Map.Entry<String, List<Map<String, Object>>> entry : categoryMap.entrySet()) {
            String categoryName = entry.getKey();
            List<Map<String, Object>> flavors = entry.getValue();

            // Limit "other" category to top 10 flavors to reduce visual clutter
            if ("other".equals(categoryName) && flavors.size() > 10) {
                flavors = flavors.subList(0, 10);
            }

            Map<String, Object> categoryData = new HashMap<>();
            categoryData.put("name", categoryName);
            categoryData.put("productCount", categoryCounts.get(categoryName));
            categoryData.put("flavors", flavors);

            categories.add(categoryData);
            totalProducts += categoryCounts.get(categoryName);
            totalFlavors += flavors.size();
        }

        // Sort categories by product count (descending)
        categories.sort((a, b) -> Integer.compare((int) b.get("productCount"), (int) a.get("productCount")));

        System.out.println("Processed " + processedRows + " rows, skipped " + skippedRows + " rows");
        System.out.println("Final categories: " + categories.size());
        System.out.println("Total flavors: " + totalFlavors);
        System.out.println("Total products: " + totalProducts);

        Map<String, Object> response = new HashMap<>();
        response.put("categories", categories);
        response.put("totalProducts", totalProducts);
        response.put("totalCategories", categories.size());
        response.put("totalFlavors", totalFlavors);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/products")
    @Operation(summary = "Search products by category or flavor",
               description = "Query products by SCA category name or specific flavor name")
    public ResponseEntity<Map<String, Object>> searchProducts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String flavor) {

        List<ProductNode> products;
        String searchType;
        String searchValue;

        if (flavor != null && !flavor.isEmpty()) {
            products = productNodeRepository.findByFlavorName(flavor.toLowerCase());
            searchType = "flavor";
            searchValue = flavor;
        } else if (category != null && !category.isEmpty()) {
            products = productNodeRepository.findBySCACategory(category.toLowerCase());
            searchType = "category";
            searchValue = category;
        } else {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Either 'category' or 'flavor' parameter is required"));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("searchType", searchType);
        response.put("searchValue", searchValue);
        response.put("productCount", products.size());
        response.put("products", products);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/search")
    @Operation(summary = "Multi-flavor search",
               description = "Search products by multiple flavors with AND/OR logic")
    public ResponseEntity<Map<String, Object>> multiFlavorSearch(
            @RequestParam List<String> flavors,
            @RequestParam(defaultValue = "false") boolean matchAll) {

        if (flavors == null || flavors.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "'flavors' parameter is required"));
        }

        // Normalize flavor names to lowercase
        List<String> normalizedFlavors = flavors.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toList());

        List<ProductNode> products;

        if (matchAll) {
            // AND logic - products must have ALL flavors
            products = productNodeRepository.findByAllFlavors(normalizedFlavors, normalizedFlavors.size());
        } else {
            // OR logic - products with ANY of the flavors
            products = productNodeRepository.findByAnyFlavor(normalizedFlavors);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("flavors", normalizedFlavors);
        response.put("matchAll", matchAll);
        response.put("productCount", products.size());
        response.put("products", products);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/categories")
    @Operation(summary = "Get all SCA categories",
               description = "Returns list of all SCA category names")
    public ResponseEntity<List<String>> getAllCategories() {
        List<String> categories = Arrays.asList(
            "fruity", "floral", "sweet", "nutty", "spices", "roasted", "green", "sour", "other"
        );
        return ResponseEntity.ok(categories);
    }

    @GetMapping("/debug/raw-flavor-data")
    @Operation(summary = "Debug endpoint - see raw flavor data",
               description = "Returns raw flavor data from repository for debugging")
    public ResponseEntity<Map<String, Object>> debugRawFlavorData() {
        // Return raw map data directly from query (TastingNoteNode 4-tier hierarchy)
        List<Map<String, Object>> rawData = tastingNoteNodeRepository.findAllTastingNotesWithProductCountsAsMap();

        // Log first item for debugging
        if (!rawData.isEmpty()) {
            System.out.println("First row keys: " + rawData.get(0).keySet());
            System.out.println("First row: " + rawData.get(0));
        }

        Map<String, Object> debugResponse = new HashMap<>();
        debugResponse.put("rowCount", rawData.size());
        debugResponse.put("firstRowKeys", rawData.isEmpty() ? null : rawData.get(0).keySet());
        debugResponse.put("firstRow", rawData.isEmpty() ? null : rawData.get(0));
        debugResponse.put("allData", rawData);

        return ResponseEntity.ok(debugResponse);
    }

    // ========================================
    // WCR SENSORY LEXICON SUBCATEGORY ENDPOINTS
    // ========================================

    @GetMapping("/subcategories")
    @Operation(summary = "Get all subcategories for a category",
               description = "Returns list of WCR Sensory Lexicon subcategories for a given category (e.g., Berry, Citrus Fruit for Fruity)")
    public ResponseEntity<Map<String, Object>> getSubcategories(
            @RequestParam String category) {

        Set<String> subcategories = scaFlavorWheelService.getSubcategoriesForCategory(category);

        Map<String, Object> response = new HashMap<>();
        response.put("category", category);
        response.put("subcategories", new ArrayList<>(subcategories));
        response.put("count", subcategories.size());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/subcategories/all")
    @Operation(summary = "Get complete WCR hierarchical structure",
               description = "Returns the full 3-tier WCR Sensory Lexicon: Category → Subcategory → Keywords")
    public ResponseEntity<Map<String, Object>> getFullHierarchy() {
        Map<String, Map<String, List<String>>> hierarchy = scaFlavorWheelService.getHierarchicalStructure();

        Map<String, Object> response = new HashMap<>();
        response.put("hierarchy", hierarchy);
        response.put("categoryCount", hierarchy.size());

        // Count total subcategories and keywords
        int totalSubcategories = 0;
        int totalKeywords = 0;
        for (Map<String, List<String>> subcats : hierarchy.values()) {
            totalSubcategories += subcats.size();
            for (List<String> keywords : subcats.values()) {
                totalKeywords += keywords.size();
            }
        }

        response.put("totalSubcategories", totalSubcategories);
        response.put("totalKeywords", totalKeywords);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/products/by-subcategory")
    @Operation(summary = "Find products by subcategory",
               description = "Search products by WCR subcategory (e.g., 'berry', 'citrus fruit', 'brown sugar')")
    public ResponseEntity<Map<String, Object>> searchProductsBySubcategory(
            @RequestParam String category,
            @RequestParam String subcategory) {

        // Get all keywords for this subcategory
        List<String> keywords = scaFlavorWheelService.getKeywordsForSubcategory(category, subcategory);

        if (keywords.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Subcategory not found: " + subcategory + " in category " + category));
        }

        // Find products that match ANY keyword in this subcategory
        Set<ProductNode> productSet = new HashSet<>();
        for (String keyword : keywords) {
            List<ProductNode> products = productNodeRepository.findByFlavorName(keyword.toLowerCase());
            productSet.addAll(products);
        }

        List<ProductNode> products = new ArrayList<>(productSet);

        Map<String, Object> response = new HashMap<>();
        response.put("category", category);
        response.put("subcategory", subcategory);
        response.put("keywords", keywords);
        response.put("productCount", products.size());
        response.put("products", products);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/flavor-info")
    @Operation(summary = "Get WCR hierarchy for a specific flavor",
               description = "Returns the category and subcategory for a given flavor/tasting note")
    public ResponseEntity<Map<String, Object>> getFlavorInfo(
            @RequestParam String flavor) {

        SCAFlavorWheelService.CategorySubcategory info = scaFlavorWheelService.findCategorySubcategory(flavor);

        Map<String, Object> response = new HashMap<>();
        response.put("flavor", flavor);
        response.put("category", info.category);
        response.put("subcategory", info.subcategory);
        response.put("hierarchy", info.category + " → " + info.subcategory);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/correlations")
    @Operation(summary = "Get correlated flavors (co-occurrence analysis)",
               description = "Returns flavors that frequently appear together with the given flavor, with co-occurrence percentages")
    public ResponseEntity<Map<String, Object>> getFlavorCorrelations(
            @RequestParam String flavor) {

        String normalizedFlavor = flavor.toLowerCase();

        List<Map<String, Object>> rawData = tastingNoteNodeRepository.findCorrelatedTastingNotes(normalizedFlavor);

        List<Map<String, Object>> correlations = new ArrayList<>();
        for (Map<String, Object> row : rawData) {
            String correlatedFlavor = (String) row.get("correlatedFlavor");
            String category = (String) row.get("category");

            Object coOccurrenceObj = row.get("coOccurrenceCount");
            Object percentageObj = row.get("percentage");

            Long coOccurrenceCount = null;
            Long percentage = null;

            if (coOccurrenceObj instanceof Long) {
                coOccurrenceCount = (Long) coOccurrenceObj;
            } else if (coOccurrenceObj instanceof Integer) {
                coOccurrenceCount = ((Integer) coOccurrenceObj).longValue();
            }

            if (percentageObj instanceof Long) {
                percentage = (Long) percentageObj;
            } else if (percentageObj instanceof Integer) {
                percentage = ((Integer) percentageObj).longValue();
            } else if (percentageObj instanceof Double) {
                percentage = ((Double) percentageObj).longValue();
            }

            if (correlatedFlavor == null || coOccurrenceCount == null || percentage == null) {
                continue;
            }

            Map<String, Object> correlation = new HashMap<>();
            correlation.put("flavor", correlatedFlavor);
            correlation.put("category", category != null ? category : "other");
            correlation.put("coOccurrenceCount", coOccurrenceCount.intValue());
            correlation.put("percentage", percentage.intValue());

            correlations.add(correlation);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("flavor", normalizedFlavor);
        response.put("correlations", correlations);
        response.put("count", correlations.size());

        return ResponseEntity.ok(response);
    }
}
