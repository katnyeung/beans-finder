package com.coffee.beansfinder.service;

import com.coffee.beansfinder.controller.MapController;
import com.coffee.beansfinder.dto.CountryFlavorDTO;
import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.graph.node.OriginNode;
import com.coffee.beansfinder.graph.node.ProducerNode;
import com.coffee.beansfinder.graph.repository.OriginNodeRepository;
import com.coffee.beansfinder.graph.repository.ProducerNodeRepository;
import com.coffee.beansfinder.graph.repository.TastingNoteNodeRepository;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing static JSON cache files for map data
 */
@Service
@Slf4j
public class MapCacheService {

    private final CoffeeBrandRepository brandRepository;
    private final CoffeeProductRepository productRepository;
    private final OriginNodeRepository originNodeRepository;
    private final ProducerNodeRepository producerNodeRepository;
    private final TastingNoteNodeRepository tastingNoteNodeRepository;

    // External cache directory (production) - set via application-prod.properties
    @Value("${cache.external.dir:#{null}}")
    private String externalCacheDir;

    // Dev directories - write to both src and target
    private static final String CACHE_DIR_SRC = "src/main/resources/static/cache/";
    private static final String CACHE_DIR_TARGET = "target/classes/static/cache/";

    public MapCacheService(CoffeeBrandRepository brandRepository,
                           CoffeeProductRepository productRepository,
                           OriginNodeRepository originNodeRepository,
                           ProducerNodeRepository producerNodeRepository,
                           TastingNoteNodeRepository tastingNoteNodeRepository) {
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.originNodeRepository = originNodeRepository;
        this.producerNodeRepository = producerNodeRepository;
        this.tastingNoteNodeRepository = tastingNoteNodeRepository;
    }

    // Cache for product counts by origin (loaded once per rebuild)
    private Map<String, Long> productCountsByOriginCache;

    /**
     * Rebuild all cache files
     */
    @Transactional(readOnly = true)
    public void rebuildAllCaches() {
        log.info("=== Starting cache rebuild ===");
        long startTime = System.currentTimeMillis();

        try {
            // Pre-load product counts once to avoid repeated findAll() calls
            preloadProductCounts();

            rebuildMapDataCache();
            rebuildFlavorDataCache();
            rebuildFlavorWheelCache();

            // Clear the cache after rebuild
            productCountsByOriginCache = null;

            long duration = System.currentTimeMillis() - startTime;
            log.info("=== Cache rebuild completed in {}ms ===", duration);
        } catch (Exception e) {
            log.error("Failed to rebuild caches", e);
            throw new RuntimeException("Cache rebuild failed", e);
        }
    }

    /**
     * Rebuild all cache files asynchronously (called after crawl to avoid blocking)
     */
    @Async
    public void rebuildAllCachesAsync() {
        log.info("=== Starting ASYNC cache rebuild ===");
        try {
            // Small delay to ensure crawl transaction is fully committed
            Thread.sleep(2000);
            rebuildAllCaches();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Async cache rebuild interrupted");
        } catch (Exception e) {
            log.error("Async cache rebuild failed", e);
        }
    }

    /**
     * Pre-load product counts by origin to avoid N+1 queries
     */
    private void preloadProductCounts() {
        log.info("Pre-loading product counts by origin...");
        productCountsByOriginCache = new HashMap<>();

        // Load all products once and group by origin+region
        List<CoffeeProduct> allProducts = productRepository.findAll();

        for (CoffeeProduct product : allProducts) {
            String origin = product.getOrigin();
            String region = product.getRegion();

            if (origin == null) continue;

            // Create key for country+region combination
            String key = createOriginKey(origin, region);
            productCountsByOriginCache.merge(key, 1L, Long::sum);

            // Also count for country-only (no region)
            String countryOnlyKey = createOriginKey(origin, null);
            if (!key.equals(countryOnlyKey)) {
                // Don't double-count if region is null
            }
        }

        log.info("Pre-loaded {} origin keys", productCountsByOriginCache.size());
    }

    /**
     * Create a unique key for origin + region combination
     */
    private String createOriginKey(String country, String region) {
        String normalizedCountry = country.toLowerCase().trim();
        if (region == null || region.trim().isEmpty()) {
            return normalizedCountry + "::";
        }
        return normalizedCountry + "::" + region.toLowerCase().trim();
    }

    /**
     * Rebuild map-data.json cache file
     */
    public void rebuildMapDataCache() {
        log.info("Rebuilding map-data.json...");

        try {
            // Build complete map data using optimized queries
            MapController.CompleteMapData mapData = buildCompleteMapData();

            // Write to appropriate directories based on environment
            writeCacheFile(mapData, "map-data.json");

            log.info("Successfully rebuilt map-data.json");
        } catch (Exception e) {
            log.error("Failed to rebuild map-data.json", e);
            throw new RuntimeException("Failed to rebuild map data cache", e);
        }
    }

    /**
     * Rebuild flavors-by-country.json cache file
     */
    public void rebuildFlavorDataCache() {
        log.info("Rebuilding flavors-by-country.json...");

        try {
            List<CountryFlavorDTO> flavorData = buildFlavorData();

            // Write to appropriate directories based on environment
            writeCacheFile(flavorData, "flavors-by-country.json");

            log.info("Successfully rebuilt flavors-by-country.json");
        } catch (Exception e) {
            log.error("Failed to rebuild flavors-by-country.json", e);
            throw new RuntimeException("Failed to rebuild flavor data cache", e);
        }
    }

    /**
     * Rebuild flavor-wheel-data.json cache file
     */
    public void rebuildFlavorWheelCache() {
        log.info("Rebuilding flavor-wheel-data.json...");

        try {
            Map<String, Object> flavorWheelData = buildFlavorWheelData();

            // Write to appropriate directories based on environment
            writeCacheFile(flavorWheelData, "flavor-wheel-data.json");

            log.info("Successfully rebuilt flavor-wheel-data.json");
        } catch (Exception e) {
            log.error("Failed to rebuild flavor-wheel-data.json", e);
            throw new RuntimeException("Failed to rebuild flavor wheel data cache", e);
        }
    }

    /**
     * Write cache file to appropriate directory based on environment.
     * In production (externalCacheDir set): writes to external directory only.
     * In development: writes to both src and target directories.
     */
    private void writeCacheFile(Object data, String filename) throws IOException {
        if (externalCacheDir != null && !externalCacheDir.isEmpty()) {
            // Production: write to external directory
            String path = externalCacheDir + filename;
            writeJsonToFile(data, path);
            log.info("Wrote cache to external directory: {}", path);
        } else {
            // Development: write to both src and target directories
            writeJsonToFile(data, CACHE_DIR_SRC + filename);
            writeJsonToFile(data, CACHE_DIR_TARGET + filename);
        }
    }

    /**
     * Build complete map data using optimized queries (no N+1)
     */
    private MapController.CompleteMapData buildCompleteMapData() {
        // Get all approved brands with coordinates
        List<CoffeeBrand> allBrands = brandRepository.findAll().stream()
                .filter(brand -> brand.getApproved() != null && brand.getApproved())
                .filter(brand -> brand.getLatitude() != null && brand.getLongitude() != null)
                .collect(Collectors.toList());

        // Extract brand IDs
        List<Long> brandIds = allBrands.stream()
                .map(CoffeeBrand::getId)
                .collect(Collectors.toList());

        // Batch count products by brand (single query instead of N queries)
        Map<Long, Long> brandProductCounts = new HashMap<>();
        if (!brandIds.isEmpty()) {
            productRepository.countByBrandIds(brandIds).forEach(
                    result -> brandProductCounts.put(result.getBrandId(), result.getCount())
            );
        }

        // Build brand map data
        List<MapController.BrandMapData> brands = allBrands.stream()
                .map(brand -> new MapController.BrandMapData(
                        brand.getId(),
                        brand.getName(),
                        brand.getCountry(),
                        brand.getLatitude(),
                        brand.getLongitude(),
                        brand.getWebsite(),
                        brandProductCounts.getOrDefault(brand.getId(), 0L)
                ))
                .collect(Collectors.toList());

        // Batch count products by origin (single query instead of N queries)
        Map<String, Long> originProductCounts = new HashMap<>();
        productRepository.countByOrigins().forEach(
                result -> originProductCounts.put(result.getOrigin(), result.getCount())
        );

        // Get all origins with coordinates, filter non-geographic and blend origins
        List<MapController.OriginMapData> allOrigins = ((List<OriginNode>) originNodeRepository.findAll()).stream()
                .filter(origin -> origin.getLatitude() != null && origin.getLongitude() != null)
                .filter(origin -> !isNonGeographicOrigin(origin.getCountry()))
                .filter(origin -> !isNonGeographicOrigin(origin.getRegion())) // Also check region
                .map(origin -> new MapController.OriginMapData(
                        origin.getId(),
                        origin.getCountry(),
                        origin.getRegion(),
                        origin.getLatitude(),
                        origin.getLongitude(),
                        countProductsFromOrigin(origin.getCountry(), origin.getRegion())
                ))
                .filter(origin -> origin.getProductCount() > 0) // Exclude origins with no products
                .collect(Collectors.toList());

        // Deduplicate by coordinates (group by lat/lon with small tolerance)
        Map<String, MapController.OriginMapData> uniqueOrigins = new LinkedHashMap<>();
        for (MapController.OriginMapData origin : allOrigins) {
            String coordKey = String.format("%.4f,%.4f", origin.getLatitude(), origin.getLongitude());

            if (!uniqueOrigins.containsKey(coordKey)) {
                uniqueOrigins.put(coordKey, origin);
            } else {
                // Merge: keep the one with more specific region name, but SUM the product counts
                MapController.OriginMapData existing = uniqueOrigins.get(coordKey);
                long totalProducts = existing.getProductCount() + origin.getProductCount();

                if (isMoreSpecific(origin.getRegion(), existing.getRegion())) {
                    MapController.OriginMapData merged = new MapController.OriginMapData(
                            origin.getId(),
                            origin.getCountry(),
                            origin.getRegion(),
                            origin.getLatitude(),
                            origin.getLongitude(),
                            totalProducts
                    );
                    uniqueOrigins.put(coordKey, merged);
                } else {
                    MapController.OriginMapData merged = new MapController.OriginMapData(
                            existing.getId(),
                            existing.getCountry(),
                            existing.getRegion(),
                            existing.getLatitude(),
                            existing.getLongitude(),
                            totalProducts
                    );
                    uniqueOrigins.put(coordKey, merged);
                }
            }
        }

        List<MapController.OriginMapData> origins = new ArrayList<>(uniqueOrigins.values());

        // Get all producers with coordinates
        List<MapController.ProducerMapData> producers = ((List<ProducerNode>) producerNodeRepository.findAll()).stream()
                .filter(producer -> producer.getLatitude() != null && producer.getLongitude() != null)
                .map(producer -> new MapController.ProducerMapData(
                        producer.getId(),
                        producer.getName(),
                        producer.getCountry(),
                        producer.getRegion(),
                        producer.getCity(),
                        producer.getLatitude(),
                        producer.getLongitude()
                ))
                .collect(Collectors.toList());

        // Build connections
        List<MapController.ConnectionLine> connections = buildConnections(brands, origins, producers, brandIds);

        return new MapController.CompleteMapData(brands, origins, producers, connections);
    }

    /**
     * Build connections between brands, origins, and producers
     * Optimized: loads all products once and groups by brand
     */
    private List<MapController.ConnectionLine> buildConnections(
            List<MapController.BrandMapData> brands,
            List<MapController.OriginMapData> origins,
            List<MapController.ProducerMapData> producers,
            List<Long> brandIds) {

        List<MapController.ConnectionLine> connections = new ArrayList<>();

        if (brandIds.isEmpty()) {
            return connections;
        }

        // Load ALL products once (uses the same data from preloadProductCounts)
        List<CoffeeProduct> allProducts = productRepository.findAll();

        // Group products by brand ID
        Map<Long, List<CoffeeProduct>> productsByBrand = allProducts.stream()
                .filter(p -> p.getBrand() != null)
                .collect(Collectors.groupingBy(p -> p.getBrand().getId()));

        // Build connections for each brand
        for (MapController.BrandMapData brand : brands) {
            List<CoffeeProduct> products = productsByBrand.getOrDefault(brand.getId(), Collections.emptyList());

            // Brand -> Origins: Connect to specific regions only
            // Build set of (country, region) pairs from products
            Set<String> originKeys = new HashSet<>();
            Set<String> countriesWithNoRegion = new HashSet<>(); // Countries where brand has products with no region specified

            for (CoffeeProduct product : products) {
                String country = product.getOrigin();
                String region = product.getRegion();
                if (country != null) {
                    String countryLower = country.toLowerCase().trim();
                    if (region != null && !region.trim().isEmpty()) {
                        // Exact country+region key
                        String key = countryLower + "::" + region.toLowerCase().trim();
                        originKeys.add(key);
                    } else {
                        // Product has country but no region - track separately
                        countriesWithNoRegion.add(countryLower);
                    }
                }
            }

            // Connect to matching origins
            for (MapController.OriginMapData origin : origins) {
                String originCountry = origin.getCountry().toLowerCase().trim();
                String originRegion = origin.getRegion();

                boolean shouldConnect = false;

                if (originRegion != null && !originRegion.trim().isEmpty()) {
                    // Origin has a region - only connect if brand has products from this exact region
                    String originKey = originCountry + "::" + originRegion.toLowerCase().trim();
                    shouldConnect = originKeys.contains(originKey);
                } else {
                    // Origin has no region (country-level) - connect if brand has products from this country with no region
                    shouldConnect = countriesWithNoRegion.contains(originCountry);
                }

                if (shouldConnect) {
                    connections.add(new MapController.ConnectionLine(
                            "brand-origin",
                            brand.getId().toString(),
                            origin.getId(),
                            brand.getLatitude(),
                            brand.getLongitude(),
                            origin.getLatitude(),
                            origin.getLongitude()
                    ));
                }
            }

            // Origins -> Producers
            Set<String> producerNames = products.stream()
                    .map(CoffeeProduct::getProducer)
                    .filter(Objects::nonNull)
                    .filter(name -> !name.isBlank())
                    .collect(Collectors.toSet());

            for (String producerName : producerNames) {
                producers.stream()
                        .filter(producer -> producer.getName().toLowerCase().contains(producerName.toLowerCase()))
                        .findFirst() // Only one connection per producer
                        .ifPresent(producer -> {
                            origins.stream()
                                    .filter(origin -> origin.getCountry().equalsIgnoreCase(producer.getCountry()))
                                    .findFirst()
                                    .ifPresent(origin -> connections.add(new MapController.ConnectionLine(
                                            "origin-producer",
                                            origin.getId(),
                                            producer.getId(),
                                            origin.getLatitude(),
                                            origin.getLongitude(),
                                            producer.getLatitude(),
                                            producer.getLongitude()
                                    )));
                        });
            }
        }

        return connections;
    }

    /**
     * Build flavor data (uses TastingNoteNode 4-tier hierarchy)
     */
    private List<CountryFlavorDTO> buildFlavorData() {
        List<Map<String, Object>> rawData = tastingNoteNodeRepository.findTopTastingNotesByCountry();
        List<CountryFlavorDTO> result = new ArrayList<>();

        for (Map<String, Object> dataMap : rawData) {
            try {
                Map<String, Object> innerData = (Map<String, Object>) dataMap.get("data");
                if (innerData == null) innerData = dataMap;

                String country = (String) innerData.get("country");
                List<Map<String, Object>> topFlavorsRaw = (List<Map<String, Object>>) innerData.get("topFlavors");

                if (country == null || topFlavorsRaw == null) continue;

                List<CountryFlavorDTO.FlavorInfo> topFlavors = new ArrayList<>();
                for (Map<String, Object> flavorMap : topFlavorsRaw) {
                    String flavor = (String) flavorMap.get("flavor");
                    String category = (String) flavorMap.get("category");
                    Long productCount = ((Number) flavorMap.get("productCount")).longValue();
                    Double percentage = ((Number) flavorMap.get("percentage")).doubleValue();

                    topFlavors.add(new CountryFlavorDTO.FlavorInfo(flavor, category, productCount, percentage));
                }

                String countryCode = getCountryCode(country);
                result.add(new CountryFlavorDTO(countryCode, country, topFlavors));
            } catch (Exception e) {
                log.error("Error processing flavor data entry: {}", e.getMessage());
            }
        }

        return result;
    }

    /**
     * Write object to JSON file
     */
    private void writeJsonToFile(Object data, String filePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty print

        File file = new File(filePath);
        file.getParentFile().mkdirs(); // Ensure directory exists

        mapper.writeValue(file, data);

        long fileSize = file.length();
        log.info("Wrote {} ({} bytes)", filePath, fileSize);
    }

    /**
     * Count products from a specific origin by matching country AND region EXACTLY.
     * Uses pre-loaded cache to avoid N+1 queries.
     */
    private long countProductsFromOrigin(String country, String region) {
        if (productCountsByOriginCache == null) {
            log.warn("Product counts cache not initialized, returning 0");
            return 0;
        }

        String key = createOriginKey(country, region);
        return productCountsByOriginCache.getOrDefault(key, 0L);
    }

    /**
     * Determine if one region name is more specific than another.
     */
    private boolean isMoreSpecific(String region1, String region2) {
        if (region1 == null && region2 == null) return false;
        if (region1 == null) return false;
        if (region2 == null) return true;
        return region1.length() > region2.length();
    }

    /**
     * Check if origin is non-geographic (blend, multi-origin, or malformed)
     */
    private boolean isNonGeographicOrigin(String country) {
        if (country == null) return true;

        String normalized = country.trim().toLowerCase();

        // Exact matches
        if (normalized.equals("various") || normalized.equals("blend") ||
            normalized.equals("blended") || normalized.equals("mixed") ||
            normalized.equals("multiple") || normalized.equals("unknown") ||
            normalized.equals("n/a") || normalized.equals("na") ||
            normalized.equals("single origin") || normalized.isEmpty()) {
            return true;
        }

        // Pattern-based filtering
        return normalized.startsWith("blend") || normalized.contains("blend") ||
               normalized.contains(" & ") || normalized.contains(" and ") ||
               normalized.contains("%") ||
               normalized.endsWith(")") || normalized.startsWith("(") ||
               (normalized.contains(",") && normalized.split(",").length > 2);
    }

    /**
     * Get country code from country name
     */
    private String getCountryCode(String countryName) {
        if (countryName == null) return null;
        String normalized = countryName.trim().toUpperCase();

        Map<String, String> countryMap = new HashMap<>();
        countryMap.put("COLOMBIA", "CO");
        countryMap.put("ETHIOPIA", "ET");
        countryMap.put("BRAZIL", "BR");
        countryMap.put("KENYA", "KE");
        countryMap.put("GUATEMALA", "GT");
        countryMap.put("HONDURAS", "HN");
        countryMap.put("PERU", "PE");
        countryMap.put("INDONESIA", "ID");
        countryMap.put("VIETNAM", "VN");
        countryMap.put("MEXICO", "MX");
        countryMap.put("NICARAGUA", "NI");
        countryMap.put("COSTA RICA", "CR");
        countryMap.put("RWANDA", "RW");
        countryMap.put("UGANDA", "UG");
        countryMap.put("TANZANIA", "TZ");
        countryMap.put("INDIA", "IN");
        countryMap.put("PANAMA", "PA");
        countryMap.put("ECUADOR", "EC");
        countryMap.put("BOLIVIA", "BO");
        countryMap.put("VENEZUELA", "VE");
        countryMap.put("EL SALVADOR", "SV");
        countryMap.put("BURUNDI", "BI");
        countryMap.put("MALAWI", "MW");
        countryMap.put("THAILAND", "TH");
        countryMap.put("PAPUA NEW GUINEA", "PG");
        countryMap.put("YEMEN", "YE");
        countryMap.put("JAMAICA", "JM");
        countryMap.put("HAWAII", "US");
        countryMap.put("DOMINICAN REPUBLIC", "DO");
        countryMap.put("CUBA", "CU");

        return countryMap.getOrDefault(normalized, normalized.substring(0, Math.min(2, normalized.length())));
    }

    /**
     * Build flavor wheel data (uses TastingNoteNode 4-tier hierarchy)
     *
     * Structure per category:
     * - topFlavors: Top 10 flavors with ≥10 products (shown initially)
     * - moreFlavors: Remaining flavors with <10 products (hidden under "More...")
     * - moreCount: Number of hidden flavors
     */
    private Map<String, Object> buildFlavorWheelData() {
        log.info("Building flavor wheel data with top/more split...");

        // Threshold for "rare" flavors that go to "More..."
        final int RARE_THRESHOLD = 10;
        final int MAX_TOP_FLAVORS = 10;

        // Single efficient query - returns raw map data from TastingNoteNode hierarchy
        List<Map<String, Object>> allFlavorData = tastingNoteNodeRepository.findAllTastingNotesWithProductCountsAsMap();

        // Group by category
        Map<String, List<Map<String, Object>>> categoryMap = new HashMap<>();
        Map<String, Integer> categoryCounts = new HashMap<>();

        for (Map<String, Object> row : allFlavorData) {
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
                continue;
            }

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

        // Build final categories list with top/more split
        List<Map<String, Object>> categories = new ArrayList<>();
        int totalProducts = 0;
        int totalFlavors = 0;
        int totalMoreFlavors = 0;

        for (Map.Entry<String, List<Map<String, Object>>> entry : categoryMap.entrySet()) {
            String categoryName = entry.getKey();
            List<Map<String, Object>> allFlavors = entry.getValue();

            // Sort by product count descending
            allFlavors.sort((a, b) -> Integer.compare(
                    (int) b.get("productCount"),
                    (int) a.get("productCount")));

            // Split into topFlavors (≥10 products, max 10) and moreFlavors (<10 products)
            List<Map<String, Object>> topFlavors = new ArrayList<>();
            List<Map<String, Object>> moreFlavors = new ArrayList<>();

            for (Map<String, Object> flavor : allFlavors) {
                int count = (int) flavor.get("productCount");

                if (count >= RARE_THRESHOLD && topFlavors.size() < MAX_TOP_FLAVORS) {
                    topFlavors.add(flavor);
                } else {
                    moreFlavors.add(flavor);
                }
            }

            // Build category data
            Map<String, Object> categoryData = new HashMap<>();
            categoryData.put("name", categoryName);
            categoryData.put("displayName", capitalize(categoryName));
            categoryData.put("productCount", categoryCounts.get(categoryName));
            categoryData.put("topFlavors", topFlavors);
            categoryData.put("moreFlavors", moreFlavors);
            categoryData.put("moreCount", moreFlavors.size());

            categories.add(categoryData);
            totalProducts += categoryCounts.get(categoryName);
            totalFlavors += topFlavors.size();
            totalMoreFlavors += moreFlavors.size();
        }

        // Sort categories by product count (descending)
        categories.sort((a, b) -> Integer.compare((int) b.get("productCount"), (int) a.get("productCount")));

        Map<String, Object> response = new HashMap<>();
        response.put("categories", categories);
        response.put("totalProducts", totalProducts);
        response.put("totalCategories", categories.size());
        response.put("totalFlavors", totalFlavors);
        response.put("totalMoreFlavors", totalMoreFlavors);

        log.info("Built flavor wheel data: {} categories, {} top flavors, {} more flavors, {} products",
                categories.size(), totalFlavors, totalMoreFlavors, totalProducts);

        return response;
    }

    /**
     * Capitalize first letter of a string
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
}
