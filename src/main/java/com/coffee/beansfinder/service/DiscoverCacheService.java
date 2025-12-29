package com.coffee.beansfinder.service;

import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.graph.repository.TastingNoteNodeRepository;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for building Discover page cache files.
 * Generates static JSON files for zero-Neo4j page loads.
 */
@Service
@Slf4j
public class DiscoverCacheService {

    private final CoffeeProductRepository productRepository;
    private final TastingNoteNodeRepository tastingNoteNodeRepository;
    private final Neo4jClient neo4jClient;

    @Value("${cache.external.dir:#{null}}")
    private String externalCacheDir;

    private static final String CACHE_DIR_SRC = "src/main/resources/static/cache/";
    private static final String CACHE_DIR_TARGET = "target/classes/static/cache/";

    public DiscoverCacheService(CoffeeProductRepository productRepository,
                                 TastingNoteNodeRepository tastingNoteNodeRepository,
                                 Neo4jClient neo4jClient) {
        this.productRepository = productRepository;
        this.tastingNoteNodeRepository = tastingNoteNodeRepository;
        this.neo4jClient = neo4jClient;
    }

    /**
     * Rebuild all Discover page cache files
     */
    @Transactional(readOnly = true)
    public void rebuildAllDiscoverCaches() {
        log.info("=== Starting Discover cache rebuild ===");
        long startTime = System.currentTimeMillis();

        try {
            rebuildOriginsCache();
            rebuildFlavorMatrixCache();
            rebuildProcessesCache();
            rebuildRoastLevelsCache();
            rebuildVarietiesCache();
            rebuildHiddenGemsCache();
            rebuildFlavorPathsCache();
            rebuildPriceComplexityCache();
            rebuildFlavorExplorerCache();  // NEW: Flavor explorer data

            long duration = System.currentTimeMillis() - startTime;
            log.info("=== Discover cache rebuild completed in {}ms ===", duration);
        } catch (Exception e) {
            log.error("Failed to rebuild Discover caches", e);
            throw new RuntimeException("Discover cache rebuild failed", e);
        }
    }

    /**
     * Rebuild discover-origins.json
     * Contains origin stats: product count, top flavors, processes, price range
     */
    public void rebuildOriginsCache() {
        log.info("Rebuilding discover-origins.json...");

        List<CoffeeProduct> allProducts = productRepository.findAllWithBrand();

        // Group products by origin
        Map<String, List<CoffeeProduct>> productsByOrigin = allProducts.stream()
                .filter(p -> p.getOrigin() != null && !p.getOrigin().trim().isEmpty())
                .collect(Collectors.groupingBy(p -> p.getOrigin().trim()));

        List<Map<String, Object>> origins = new ArrayList<>();

        for (Map.Entry<String, List<CoffeeProduct>> entry : productsByOrigin.entrySet()) {
            String country = entry.getKey();
            List<CoffeeProduct> products = entry.getValue();

            Map<String, Object> originData = new HashMap<>();
            originData.put("country", country);
            originData.put("productCount", products.size());

            // Top flavors (from tasting notes)
            Map<String, Long> flavorCounts = new HashMap<>();
            for (CoffeeProduct p : products) {
                List<String> notes = parseTastingNotes(p);
                for (String note : notes) {
                    String normalized = note.toLowerCase().trim();
                    flavorCounts.merge(normalized, 1L, Long::sum);
                }
            }
            List<String> topFlavors = flavorCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            originData.put("topFlavors", topFlavors);

            // Top processes
            Map<String, Long> processCounts = new HashMap<>();
            for (CoffeeProduct p : products) {
                if (p.getProcess() != null && !p.getProcess().trim().isEmpty()) {
                    processCounts.merge(p.getProcess().trim(), 1L, Long::sum);
                }
            }
            List<String> topProcesses = processCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(3)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            originData.put("topProcesses", topProcesses);

            // Top regions
            Map<String, Long> regionCounts = new HashMap<>();
            for (CoffeeProduct p : products) {
                if (p.getRegion() != null && !p.getRegion().trim().isEmpty()) {
                    regionCounts.merge(p.getRegion().trim(), 1L, Long::sum);
                }
            }
            List<String> topRegions = regionCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            originData.put("topRegions", topRegions);

            // Price statistics (per 100g)
            List<BigDecimal> prices = products.stream()
                    .filter(p -> p.getPrice() != null && p.getPrice().compareTo(BigDecimal.ZERO) > 0)
                    .map(CoffeeProduct::getPrice)
                    .collect(Collectors.toList());

            if (!prices.isEmpty()) {
                BigDecimal sum = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal avg = sum.divide(BigDecimal.valueOf(prices.size()), 2, RoundingMode.HALF_UP);
                BigDecimal min = Collections.min(prices);
                BigDecimal max = Collections.max(prices);

                originData.put("avgPricePer100g", avg);
                originData.put("priceRange", Map.of("min", min, "max", max));
            }

            origins.add(originData);
        }

        // Sort by product count descending
        origins.sort((a, b) -> Integer.compare(
                (Integer) b.get("productCount"),
                (Integer) a.get("productCount")));

        Map<String, Object> result = new HashMap<>();
        result.put("origins", origins);
        result.put("generatedAt", System.currentTimeMillis());

        writeCacheFile(result, "discover-origins.json");
        log.info("Successfully rebuilt discover-origins.json with {} origins", origins.size());
    }

    /**
     * Rebuild discover-flavor-matrix.json
     * Contains flavor co-occurrence matrix for top N flavors
     */
    public void rebuildFlavorMatrixCache() {
        log.info("Rebuilding discover-flavor-matrix.json...");

        List<CoffeeProduct> allProducts = productRepository.findAllWithBrand();
        int totalProducts = allProducts.size();

        // Count all flavors
        Map<String, Long> flavorCounts = new HashMap<>();
        for (CoffeeProduct p : allProducts) {
            List<String> notes = parseTastingNotes(p);
            if (notes != null) {
                for (String note : notes) {
                    String normalized = note.toLowerCase().trim();
                    if (!normalized.isEmpty()) {
                        flavorCounts.merge(normalized, 1L, Long::sum);
                    }
                }
            }
        }

        // Get top 20 flavors (we'll generate 10x10, 15x15, 20x20 matrices)
        List<String> topFlavors = flavorCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(20)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Build co-occurrence matrix
        Map<String, Map<String, Long>> matrix = new HashMap<>();
        for (String f1 : topFlavors) {
            matrix.put(f1, new HashMap<>());
            for (String f2 : topFlavors) {
                matrix.get(f1).put(f2, 0L);
            }
        }

        // Count co-occurrences
        Set<String> topFlavorSet = new HashSet<>(topFlavors);
        for (CoffeeProduct p : allProducts) {
            List<String> notes = parseTastingNotes(p);
            if (notes == null || notes.size() < 2) continue;

            List<String> normalizedNotes = notes.stream()
                    .map(n -> n.toLowerCase().trim())
                    .filter(topFlavorSet::contains)
                    .distinct()
                    .collect(Collectors.toList());

            // Count pairs
            for (int i = 0; i < normalizedNotes.size(); i++) {
                for (int j = i + 1; j < normalizedNotes.size(); j++) {
                    String f1 = normalizedNotes.get(i);
                    String f2 = normalizedNotes.get(j);

                    // Increment both directions (symmetric matrix)
                    matrix.get(f1).merge(f2, 1L, Long::sum);
                    matrix.get(f2).merge(f1, 1L, Long::sum);
                }
            }
        }

        // Find max co-occurrence for color scaling
        long maxCooccurrence = 0;
        for (String f1 : topFlavors) {
            for (String f2 : topFlavors) {
                if (!f1.equals(f2)) {
                    maxCooccurrence = Math.max(maxCooccurrence, matrix.get(f1).get(f2));
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("flavorNames", topFlavors);
        result.put("cooccurrenceMatrix", matrix);
        result.put("maxCooccurrence", maxCooccurrence);
        result.put("totalProducts", totalProducts);
        result.put("generatedAt", System.currentTimeMillis());

        writeCacheFile(result, "discover-flavor-matrix.json");
        log.info("Successfully rebuilt discover-flavor-matrix.json with {} flavors, max co-occurrence: {}",
                topFlavors.size(), maxCooccurrence);
    }

    /**
     * Rebuild discover-processes.json
     * Contains process comparison data
     */
    public void rebuildProcessesCache() {
        log.info("Rebuilding discover-processes.json...");

        List<CoffeeProduct> allProducts = productRepository.findAllWithBrand();

        // Group by process
        Map<String, List<CoffeeProduct>> productsByProcess = allProducts.stream()
                .filter(p -> p.getProcess() != null && !p.getProcess().trim().isEmpty())
                .collect(Collectors.groupingBy(p -> normalizeProcess(p.getProcess())));

        List<Map<String, Object>> processes = new ArrayList<>();

        Map<String, String> processDescriptions = Map.of(
                "washed", "Bright, clean cup with pronounced acidity. Highlights origin terroir.",
                "natural", "Fruity, wine-like, complex fermentation notes. Full body.",
                "honey", "Sweet, balanced, fruit-forward. Between washed and natural.",
                "anaerobic", "Unique fermentation flavors, often tropical or wine-like.",
                "carbonic maceration", "Bubbly, fruity, candy-like sweetness."
        );

        for (Map.Entry<String, List<CoffeeProduct>> entry : productsByProcess.entrySet()) {
            String process = entry.getKey();
            List<CoffeeProduct> products = entry.getValue();

            if (products.size() < 1) continue; // Include all processes with at least 1 product

            Map<String, Object> processData = new HashMap<>();
            processData.put("name", process);
            processData.put("productCount", products.size());
            processData.put("description", processDescriptions.getOrDefault(process.toLowerCase(), ""));

            // Top flavors for this process
            Map<String, Long> flavorCounts = new HashMap<>();
            for (CoffeeProduct p : products) {
                List<String> notes = parseTastingNotes(p);
                if (notes != null) {
                    for (String note : notes) {
                        flavorCounts.merge(note.toLowerCase().trim(), 1L, Long::sum);
                    }
                }
            }
            List<String> typicalFlavors = flavorCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            processData.put("typicalFlavors", typicalFlavors);

            processes.add(processData);
        }

        // Sort by product count
        processes.sort((a, b) -> Integer.compare(
                (Integer) b.get("productCount"),
                (Integer) a.get("productCount")));

        Map<String, Object> result = new HashMap<>();
        result.put("processes", processes);
        result.put("generatedAt", System.currentTimeMillis());

        writeCacheFile(result, "discover-processes.json");
        log.info("Successfully rebuilt discover-processes.json with {} processes", processes.size());
    }

    /**
     * Rebuild discover-roast-levels.json
     * Contains roast level distribution data
     */
    public void rebuildRoastLevelsCache() {
        log.info("Rebuilding discover-roast-levels.json...");

        List<CoffeeProduct> allProducts = productRepository.findAllActiveWithBrand();

        // Group by roast level
        Map<String, List<CoffeeProduct>> productsByRoast = allProducts.stream()
                .filter(p -> p.getRoastLevel() != null && !p.getRoastLevel().trim().isEmpty()
                        && !p.getRoastLevel().equalsIgnoreCase("unknown"))
                .collect(Collectors.groupingBy(p -> normalizeRoastLevel(p.getRoastLevel())));

        List<Map<String, Object>> roastLevels = new ArrayList<>();

        // Define roast level order
        List<String> roastOrder = List.of("Light", "Medium", "Medium-Dark", "Dark", "Omni");

        for (String roast : roastOrder) {
            List<CoffeeProduct> products = productsByRoast.get(roast);
            if (products == null || products.isEmpty()) continue;

            Map<String, Object> roastData = new HashMap<>();
            roastData.put("name", roast);
            roastData.put("productCount", products.size());

            // Top flavors for this roast level
            Map<String, Long> flavorCounts = new HashMap<>();
            for (CoffeeProduct p : products) {
                List<String> notes = parseTastingNotes(p);
                if (notes != null) {
                    for (String note : notes) {
                        flavorCounts.merge(note.toLowerCase().trim(), 1L, Long::sum);
                    }
                }
            }
            List<String> typicalFlavors = flavorCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            roastData.put("typicalFlavors", typicalFlavors);

            roastLevels.add(roastData);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("roastLevels", roastLevels);
        result.put("generatedAt", System.currentTimeMillis());

        writeCacheFile(result, "discover-roast-levels.json");
        log.info("Successfully rebuilt discover-roast-levels.json with {} roast levels", roastLevels.size());
    }

    /**
     * Normalize roast level to standard naming
     */
    private String normalizeRoastLevel(String roastLevel) {
        if (roastLevel == null) return "Unknown";
        String lower = roastLevel.toLowerCase().trim();

        if (lower.contains("light")) return "Light";
        if (lower.contains("medium-dark") || lower.contains("medium dark")) return "Medium-Dark";
        if (lower.contains("medium")) return "Medium";
        if (lower.contains("dark")) return "Dark";
        if (lower.contains("omni")) return "Omni";

        return roastLevel; // Return as-is if no match
    }

    /**
     * Rebuild discover-varieties.json
     * Contains variety spotlight data
     */
    public void rebuildVarietiesCache() {
        log.info("Rebuilding discover-varieties.json...");

        List<CoffeeProduct> allProducts = productRepository.findAllWithBrand();

        // Group by variety
        Map<String, List<CoffeeProduct>> productsByVariety = allProducts.stream()
                .filter(p -> p.getVariety() != null && !p.getVariety().trim().isEmpty())
                .collect(Collectors.groupingBy(p -> normalizeVariety(p.getVariety())));

        List<Map<String, Object>> varieties = new ArrayList<>();

        for (Map.Entry<String, List<CoffeeProduct>> entry : productsByVariety.entrySet()) {
            String variety = entry.getKey();
            List<CoffeeProduct> products = entry.getValue();

            if (products.size() < 2) continue; // Skip very rare varieties

            Map<String, Object> varietyData = new HashMap<>();
            varietyData.put("name", variety);
            varietyData.put("productCount", products.size());

            // Top flavors
            Map<String, Long> flavorCounts = new HashMap<>();
            for (CoffeeProduct p : products) {
                List<String> notes = parseTastingNotes(p);
                if (notes != null) {
                    for (String note : notes) {
                        flavorCounts.merge(note.toLowerCase().trim(), 1L, Long::sum);
                    }
                }
            }
            List<String> typicalFlavors = flavorCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            varietyData.put("typicalFlavors", typicalFlavors);

            // Origins where this variety is found
            Set<String> origins = products.stream()
                    .map(CoffeeProduct::getOrigin)
                    .filter(o -> o != null && !o.trim().isEmpty())
                    .collect(Collectors.toSet());
            varietyData.put("origins", new ArrayList<>(origins));

            // Average price
            List<BigDecimal> prices = products.stream()
                    .filter(p -> p.getPrice() != null && p.getPrice().compareTo(BigDecimal.ZERO) > 0)
                    .map(CoffeeProduct::getPrice)
                    .collect(Collectors.toList());

            if (!prices.isEmpty()) {
                BigDecimal sum = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal avg = sum.divide(BigDecimal.valueOf(prices.size()), 2, RoundingMode.HALF_UP);
                varietyData.put("avgPrice", avg);
            }

            varieties.add(varietyData);
        }

        // Sort by product count
        varieties.sort((a, b) -> Integer.compare(
                (Integer) b.get("productCount"),
                (Integer) a.get("productCount")));

        Map<String, Object> result = new HashMap<>();
        result.put("varieties", varieties.stream().limit(15).collect(Collectors.toList()));
        result.put("generatedAt", System.currentTimeMillis());

        writeCacheFile(result, "discover-varieties.json");
        log.info("Successfully rebuilt discover-varieties.json with {} varieties", Math.min(varieties.size(), 15));
    }

    /**
     * Normalize process name
     */
    private String normalizeProcess(String process) {
        if (process == null) return "Unknown";
        String normalized = process.trim().toLowerCase();

        // Map variations to standard names
        if (normalized.contains("wash")) return "Washed";
        if (normalized.contains("natural") || normalized.contains("dry")) return "Natural";
        if (normalized.contains("honey") || normalized.contains("pulped")) return "Honey";
        if (normalized.contains("anaerobic")) return "Anaerobic";
        if (normalized.contains("carbonic")) return "Carbonic Maceration";

        // Capitalize first letter
        return process.substring(0, 1).toUpperCase() + process.substring(1).toLowerCase();
    }

    /**
     * Normalize variety name
     */
    private String normalizeVariety(String variety) {
        if (variety == null) return "Unknown";
        String normalized = variety.trim().toLowerCase();

        // Map common variations
        if (normalized.contains("geisha") || normalized.contains("gesha")) return "Geisha";
        if (normalized.contains("bourbon")) return "Bourbon";
        if (normalized.contains("typica")) return "Typica";
        if (normalized.contains("caturra")) return "Caturra";
        if (normalized.contains("catuai")) return "Catuai";
        if (normalized.contains("sl28")) return "SL28";
        if (normalized.contains("sl34")) return "SL34";
        if (normalized.contains("pacamara")) return "Pacamara";

        // Capitalize first letter
        return variety.substring(0, 1).toUpperCase() + variety.substring(1);
    }

    /**
     * Rebuild discover-hidden-gems.json
     * Finds underrated origins with good flavor complexity at competitive prices
     */
    public void rebuildHiddenGemsCache() {
        log.info("Rebuilding discover-hidden-gems.json...");

        List<CoffeeProduct> allProducts = productRepository.findAllWithBrand();

        // Group by origin
        Map<String, List<CoffeeProduct>> productsByOrigin = allProducts.stream()
                .filter(p -> p.getOrigin() != null && !p.getOrigin().trim().isEmpty())
                .collect(Collectors.groupingBy(p -> p.getOrigin().trim()));

        // Calculate complexity and value scores for each origin
        List<Map<String, Object>> gems = new ArrayList<>();

        for (Map.Entry<String, List<CoffeeProduct>> entry : productsByOrigin.entrySet()) {
            String origin = entry.getKey();
            List<CoffeeProduct> products = entry.getValue();

            // Skip if too few or too many products (we want "hidden" gems)
            if (products.size() < 3 || products.size() > 30) continue;

            // Calculate average flavor complexity (number of distinct tasting notes)
            double totalComplexity = 0;
            int complexityCount = 0;
            for (CoffeeProduct p : products) {
                List<String> notes = parseTastingNotes(p);
                if (!notes.isEmpty()) {
                    totalComplexity += notes.size();
                    complexityCount++;
                }
            }
            double avgComplexity = complexityCount > 0 ? totalComplexity / complexityCount : 0;

            // Calculate average price
            List<BigDecimal> prices = products.stream()
                    .filter(p -> p.getPrice() != null && p.getPrice().compareTo(BigDecimal.ZERO) > 0)
                    .map(CoffeeProduct::getPrice)
                    .collect(Collectors.toList());

            if (prices.isEmpty()) continue;

            BigDecimal avgPrice = prices.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(prices.size()), 2, RoundingMode.HALF_UP);

            // Hidden gem score: high complexity, low price, fewer products
            // Score = (complexity / price) * (1 / log(productCount + 1))
            double priceValue = avgPrice.doubleValue() > 0 ? avgPrice.doubleValue() : 1;
            double gemScore = (avgComplexity / priceValue) * (1.0 / Math.log(products.size() + 1));

            Map<String, Object> gem = new HashMap<>();
            gem.put("type", "origin");
            gem.put("name", origin);
            gem.put("productCount", products.size());
            gem.put("avgComplexity", Math.round(avgComplexity * 100) / 100.0);
            gem.put("avgPrice", avgPrice);
            gem.put("gemScore", Math.round(gemScore * 1000) / 1000.0);
            gem.put("reason", String.format("%.1f flavor notes avg at £%.2f/100g", avgComplexity, avgPrice));

            gems.add(gem);
        }

        // Sort by gem score descending and take top 10
        gems.sort((a, b) -> Double.compare(
                (Double) b.get("gemScore"),
                (Double) a.get("gemScore")));

        Map<String, Object> result = new HashMap<>();
        result.put("gems", gems.stream().limit(10).collect(Collectors.toList()));
        result.put("generatedAt", System.currentTimeMillis());

        writeCacheFile(result, "discover-hidden-gems.json");
        log.info("Successfully rebuilt discover-hidden-gems.json with {} gems", Math.min(gems.size(), 10));
    }

    /**
     * Rebuild discover-flavor-paths.json
     * Builds flavor journey progressions based on co-occurrence
     */
    public void rebuildFlavorPathsCache() {
        log.info("Rebuilding discover-flavor-paths.json...");

        List<CoffeeProduct> allProducts = productRepository.findAllWithBrand();

        // Build co-occurrence counts
        Map<String, Map<String, Long>> cooccurrence = new HashMap<>();
        Map<String, Long> flavorCounts = new HashMap<>();

        for (CoffeeProduct p : allProducts) {
            List<String> notes = parseTastingNotes(p);
            if (notes.size() < 2) continue;

            List<String> normalizedNotes = notes.stream()
                    .map(n -> n.toLowerCase().trim())
                    .distinct()
                    .collect(Collectors.toList());

            // Count individual flavors
            for (String note : normalizedNotes) {
                flavorCounts.merge(note, 1L, Long::sum);
            }

            // Count co-occurrences
            for (int i = 0; i < normalizedNotes.size(); i++) {
                for (int j = i + 1; j < normalizedNotes.size(); j++) {
                    String f1 = normalizedNotes.get(i);
                    String f2 = normalizedNotes.get(j);

                    cooccurrence.computeIfAbsent(f1, k -> new HashMap<>()).merge(f2, 1L, Long::sum);
                    cooccurrence.computeIfAbsent(f2, k -> new HashMap<>()).merge(f1, 1L, Long::sum);
                }
            }
        }

        // Get top 10 starting flavors
        List<String> topFlavors = flavorCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Build journeys for each top flavor
        List<Map<String, Object>> journeys = new ArrayList<>();

        for (String startFlavor : topFlavors) {
            Map<String, Long> related = cooccurrence.getOrDefault(startFlavor, Collections.emptyMap());
            long startCount = flavorCounts.getOrDefault(startFlavor, 1L);

            // Get top 3 next flavors
            List<Map<String, Object>> paths = related.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(3)
                    .map(entry -> {
                        String nextFlavor = entry.getKey();
                        double confidence = (double) entry.getValue() / startCount;

                        // Find what comes after nextFlavor
                        Map<String, Long> nextRelated = cooccurrence.getOrDefault(nextFlavor, Collections.emptyMap());
                        List<String> thenFlavors = nextRelated.entrySet().stream()
                                .filter(e -> !e.getKey().equals(startFlavor))
                                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                                .limit(3)
                                .map(Map.Entry::getKey)
                                .collect(Collectors.toList());

                        Map<String, Object> path = new HashMap<>();
                        path.put("next", nextFlavor);
                        path.put("confidence", Math.round(confidence * 100) / 100.0);
                        path.put("then", thenFlavors);
                        return path;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> journey = new HashMap<>();
            journey.put("start", startFlavor);
            journey.put("paths", paths);
            journeys.add(journey);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("journeys", journeys);
        result.put("generatedAt", System.currentTimeMillis());

        writeCacheFile(result, "discover-flavor-paths.json");
        log.info("Successfully rebuilt discover-flavor-paths.json with {} journeys", journeys.size());
    }

    /**
     * Rebuild discover-price-complexity.json
     * Scatter plot data: price vs flavor complexity
     */
    public void rebuildPriceComplexityCache() {
        log.info("Rebuilding discover-price-complexity.json...");

        List<CoffeeProduct> allProducts = productRepository.findAllWithBrand();

        List<Map<String, Object>> products = new ArrayList<>();

        for (CoffeeProduct p : allProducts) {
            if (p.getPrice() == null || p.getPrice().compareTo(BigDecimal.ZERO) <= 0) continue;

            List<String> notes = parseTastingNotes(p);
            if (notes.isEmpty()) continue;

            // Complexity score based on number of distinct tasting notes
            double complexityScore = Math.min(notes.size() / 10.0, 1.0); // Normalize to 0-1

            Map<String, Object> product = new HashMap<>();
            product.put("id", p.getId());
            product.put("name", p.getProductName());
            product.put("brand", p.getBrand() != null ? p.getBrand().getName() : null);
            product.put("pricePer100g", p.getPrice());
            product.put("complexityScore", Math.round(complexityScore * 100) / 100.0);
            product.put("origin", p.getOrigin());
            product.put("flavorCount", notes.size());

            products.add(product);
        }

        // Sort by complexity score descending
        products.sort((a, b) -> Double.compare(
                (Double) b.get("complexityScore"),
                (Double) a.get("complexityScore")));

        Map<String, Object> result = new HashMap<>();
        result.put("products", products.stream().limit(200).collect(Collectors.toList())); // Limit to 200 for performance
        result.put("totalProducts", products.size());
        result.put("generatedAt", System.currentTimeMillis());

        writeCacheFile(result, "discover-price-complexity.json");
        log.info("Successfully rebuilt discover-price-complexity.json with {} products", Math.min(products.size(), 200));
    }

    /**
     * Parse tasting notes JSON string to List
     */
    private List<String> parseTastingNotes(CoffeeProduct product) {
        if (product.getTastingNotesJson() == null || product.getTastingNotesJson().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(
                    product.getTastingNotesJson(),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class)
            );
        } catch (Exception e) {
            log.warn("Failed to parse tasting notes for product {}: {}", product.getId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Rebuild discover-flavor-explorer.json
     * Contains all flavor categories, flavors, and products for each flavor
     * This is the main cache that eliminates Neo4j queries for the flavor explorer
     */
    public void rebuildFlavorExplorerCache() {
        log.info("Rebuilding discover-flavor-explorer.json...");

        // Get SCA flavor categories with product counts from Neo4j
        String categoriesCypher = """
            MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote)
            WHERE tn.scaCategoryName IS NOT NULL
            AND (p.deleted IS NULL OR p.deleted = false)
            WITH tn.scaCategoryName as category, count(DISTINCT p) as productCount
            RETURN category, productCount
            ORDER BY productCount DESC
            """;

        Collection<Map<String, Object>> categoryResults = neo4jClient.query(categoriesCypher)
                .fetch()
                .all();

        Map<String, String> categoryEmojis = Map.of(
                "fruity", "🍎",
                "floral", "🌸",
                "sweet", "🍯",
                "roasted", "☕",
                "nutty/cocoa", "🥜",
                "spices", "🌶️",
                "green/vegetative", "🌿",
                "sour/fermented", "🍋",
                "other", "✨"
        );

        List<Map<String, Object>> categories = new ArrayList<>();
        for (Map<String, Object> row : categoryResults) {
            String category = (String) row.get("category");
            if (category != null) {
                Map<String, Object> cat = new HashMap<>();
                cat.put("name", category);
                cat.put("productCount", row.get("productCount"));
                cat.put("emoji", categoryEmojis.getOrDefault(category.toLowerCase(), "🔘"));
                categories.add(cat);
            }
        }

        // Get all flavors grouped by category with products
        Map<String, List<Map<String, Object>>> flavorsByCategory = new HashMap<>();

        for (Map<String, Object> category : categories) {
            String categoryName = (String) category.get("name");

            String flavorsCypher = """
                MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote)
                WHERE toLower(tn.scaCategoryName) = toLower($category)
                AND (p.deleted IS NULL OR p.deleted = false)
                WITH tn.id as flavorId, tn.rawText as flavorName,
                     count(DISTINCT p) as productCount
                RETURN flavorId, flavorName, productCount
                ORDER BY productCount DESC
                """;

            Collection<Map<String, Object>> flavorResults = neo4jClient.query(flavorsCypher)
                    .bind(categoryName).to("category")
                    .fetch()
                    .all();

            List<Map<String, Object>> flavors = new ArrayList<>();
            for (Map<String, Object> row : flavorResults) {
                Map<String, Object> flavor = new HashMap<>();
                flavor.put("id", row.get("flavorId"));
                flavor.put("name", row.get("flavorName"));
                flavor.put("productCount", row.get("productCount"));
                flavors.add(flavor);
            }
            flavorsByCategory.put(categoryName.toLowerCase(), flavors);
        }

        // Get products and related flavors for each flavor (all flavors with 2+ products)
        Map<String, List<Map<String, Object>>> productsByFlavor = new HashMap<>();
        Map<String, List<Map<String, Object>>> relatedFlavorsByFlavor = new HashMap<>();

        // Get all flavors that have at least 2 products (cache everything useful)
        String topFlavorsCypher = """
            MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote)
            WHERE p.deleted IS NULL OR p.deleted = false
            WITH tn.id as flavorId, count(DISTINCT p) as cnt
            WHERE cnt >= 2
            ORDER BY cnt DESC
            RETURN flavorId
            """;

        Collection<Map<String, Object>> topFlavorResults = neo4jClient.query(topFlavorsCypher)
                .fetch()
                .all();

        for (Map<String, Object> flavorRow : topFlavorResults) {
            String flavorId = (String) flavorRow.get("flavorId");

            String productsCypher = """
                MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote {id: $flavor})
                WHERE p.deleted IS NULL OR p.deleted = false
                OPTIONAL MATCH (p)-[:SOLD_BY]->(b:Brand)
                OPTIONAL MATCH (p)-[:FROM_ORIGIN]->(o:Origin)
                WITH p, b, COLLECT(DISTINCT o.country) as origins
                RETURN DISTINCT p.productId as productId, p.productName as productName,
                       b.name as brandName, origins,
                       p.roastLevel as roastLevel, p.price as price
                LIMIT 20
                """;

            Collection<Map<String, Object>> productResults = neo4jClient.query(productsCypher)
                    .bind(flavorId).to("flavor")
                    .fetch()
                    .all();

            List<Map<String, Object>> products = new ArrayList<>();
            for (Map<String, Object> row : productResults) {
                Map<String, Object> product = new HashMap<>();
                product.put("id", row.get("productId"));
                product.put("productName", row.get("productName"));
                product.put("brandName", row.get("brandName"));
                @SuppressWarnings("unchecked")
                List<String> origins = (List<String>) row.get("origins");
                if (origins != null && !origins.isEmpty()) {
                    String originStr = origins.stream()
                            .filter(o -> o != null)
                            .collect(Collectors.joining(" / "));
                    product.put("origin", originStr);
                } else {
                    product.put("origin", null);
                }
                product.put("roastLevel", row.get("roastLevel"));
                product.put("price", row.get("price"));
                products.add(product);
            }
            productsByFlavor.put(flavorId, products);

            // Get related flavors (co-occurring flavors for drill-down)
            String relatedFlavorsCypher = """
                MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote {id: $flavor})
                WHERE p.deleted IS NULL OR p.deleted = false
                MATCH (p)-[:HAS_TASTING_NOTE]->(other:TastingNote)
                WHERE other.id <> $flavor
                WITH other.id as flavorId, other.rawText as flavorName, count(DISTINCT p) as productCount
                ORDER BY productCount DESC
                LIMIT 15
                RETURN flavorId, flavorName, productCount
                """;

            Collection<Map<String, Object>> relatedResults = neo4jClient.query(relatedFlavorsCypher)
                    .bind(flavorId).to("flavor")
                    .fetch()
                    .all();

            List<Map<String, Object>> relatedFlavors = new ArrayList<>();
            for (Map<String, Object> row : relatedResults) {
                Map<String, Object> related = new HashMap<>();
                related.put("id", row.get("flavorId"));
                related.put("name", row.get("flavorName"));
                related.put("productCount", row.get("productCount"));
                relatedFlavors.add(related);
            }
            relatedFlavorsByFlavor.put(flavorId, relatedFlavors);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("categories", categories);
        result.put("flavorsByCategory", flavorsByCategory);
        result.put("productsByFlavor", productsByFlavor);
        result.put("relatedFlavorsByFlavor", relatedFlavorsByFlavor);
        result.put("generatedAt", System.currentTimeMillis());

        writeCacheFile(result, "discover-flavor-explorer.json");
        log.info("Successfully rebuilt discover-flavor-explorer.json with {} categories, {} flavors cached (all with 2+ products, with related flavors)",
                categories.size(), productsByFlavor.size());
    }

    /**
     * Write cache file to appropriate directories
     */
    private void writeCacheFile(Object data, String filename) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        try {
            // Production: write to external directory only
            if (externalCacheDir != null && !externalCacheDir.isEmpty()) {
                File externalFile = new File(externalCacheDir, filename);
                mapper.writeValue(externalFile, data);
                log.info("Wrote {} to {}", filename, externalFile.getAbsolutePath());
            } else {
                // Development: write to both src and target
                File srcDir = new File(CACHE_DIR_SRC);
                File targetDir = new File(CACHE_DIR_TARGET);

                srcDir.mkdirs();
                targetDir.mkdirs();

                File srcFile = new File(srcDir, filename);
                File targetFile = new File(targetDir, filename);

                mapper.writeValue(srcFile, data);
                mapper.writeValue(targetFile, data);

                log.info("Wrote {} to dev directories", filename);
            }
        } catch (IOException e) {
            log.error("Failed to write cache file: {}", filename, e);
            throw new RuntimeException("Failed to write cache file: " + filename, e);
        }
    }

    /**
     * Get flavor drilldown data with Redis caching
     * Cache key is based on sorted flavor list to ensure consistent keys
     */
    @Cacheable(value = "flavorDrilldown", key = "#flavors.stream().sorted().toList().toString() + '_' + #limit")
    @Transactional(readOnly = true)
    public Map<String, Object> getFlavorDrilldown(List<String> flavors, int limit) {
        log.info("Cache MISS - fetching flavor drilldown from Neo4j for: {}", flavors);

        // Normalize flavors to lowercase
        List<String> normalizedFlavors = flavors.stream()
                .map(f -> f.toLowerCase().trim())
                .collect(Collectors.toList());

        // Find products with ALL selected flavors
        String productsCypher = """
            MATCH (p:Product)
            WHERE (p.deleted IS NULL OR p.deleted = false)
            AND ALL(f IN $flavors WHERE EXISTS {
                MATCH (p)-[:HAS_TASTING_NOTE]->(tn:TastingNote)
                WHERE tn.id = f
            })
            OPTIONAL MATCH (p)-[:SOLD_BY]->(b:Brand)
            OPTIONAL MATCH (p)-[:FROM_ORIGIN]->(o:Origin)
            WITH p, b, COLLECT(DISTINCT o.country) as origins
            RETURN DISTINCT p.productId as productId, p.productName as productName,
                   b.name as brandName, origins,
                   p.roastLevel as roastLevel, p.price as price
            LIMIT $limit
            """;

        Collection<Map<String, Object>> productResults = neo4jClient.query(productsCypher)
                .bind(normalizedFlavors).to("flavors")
                .bind(limit).to("limit")
                .fetch()
                .all();

        List<Map<String, Object>> products = new ArrayList<>();
        for (Map<String, Object> row : productResults) {
            Map<String, Object> product = new HashMap<>();
            product.put("id", row.get("productId"));
            product.put("productName", row.get("productName"));
            product.put("brandName", row.get("brandName"));
            @SuppressWarnings("unchecked")
            List<String> origins = (List<String>) row.get("origins");
            if (origins != null && !origins.isEmpty()) {
                String originStr = origins.stream()
                        .filter(o -> o != null)
                        .collect(Collectors.joining(" / "));
                product.put("origin", originStr);
            } else {
                product.put("origin", null);
            }
            product.put("roastLevel", row.get("roastLevel"));
            product.put("price", row.get("price"));
            products.add(product);
        }

        // Find related flavors
        String relatedCypher = """
            MATCH (p:Product)
            WHERE (p.deleted IS NULL OR p.deleted = false)
            AND ALL(f IN $flavors WHERE EXISTS {
                MATCH (p)-[:HAS_TASTING_NOTE]->(tn:TastingNote)
                WHERE tn.id = f
            })
            MATCH (p)-[:HAS_TASTING_NOTE]->(related:TastingNote)
            WHERE NOT related.id IN $flavors
            WITH related.id as flavorId, related.rawText as flavorName,
                 related.scaCategoryName as category, count(DISTINCT p) as productCount
            RETURN flavorId, flavorName, category, productCount,
                   CASE WHEN productCount <= 3 THEN true ELSE false END as isRare
            ORDER BY productCount DESC
            LIMIT 30
            """;

        Collection<Map<String, Object>> relatedResults = neo4jClient.query(relatedCypher)
                .bind(normalizedFlavors).to("flavors")
                .fetch()
                .all();

        List<Map<String, Object>> relatedFlavors = new ArrayList<>();
        List<Map<String, Object>> rareFlavors = new ArrayList<>();

        for (Map<String, Object> row : relatedResults) {
            Map<String, Object> flavor = new HashMap<>();
            flavor.put("id", row.get("flavorId"));
            flavor.put("name", row.get("flavorName"));
            flavor.put("category", row.get("category"));
            flavor.put("productCount", row.get("productCount"));
            flavor.put("isRare", row.get("isRare"));

            if (Boolean.TRUE.equals(row.get("isRare"))) {
                rareFlavors.add(flavor);
            } else {
                relatedFlavors.add(flavor);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("selectedFlavors", flavors);
        result.put("relatedFlavors", relatedFlavors);
        result.put("rareFlavors", rareFlavors);
        result.put("products", products);
        result.put("totalProducts", products.size());

        return result;
    }
}
