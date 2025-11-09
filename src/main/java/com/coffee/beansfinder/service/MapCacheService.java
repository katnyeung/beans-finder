package com.coffee.beansfinder.service;

import com.coffee.beansfinder.controller.MapController;
import com.coffee.beansfinder.dto.CountryFlavorDTO;
import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.graph.node.OriginNode;
import com.coffee.beansfinder.graph.node.ProducerNode;
import com.coffee.beansfinder.graph.repository.FlavorNodeRepository;
import com.coffee.beansfinder.graph.repository.OriginNodeRepository;
import com.coffee.beansfinder.graph.repository.ProducerNodeRepository;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing static JSON cache files for map data
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MapCacheService {

    private final CoffeeBrandRepository brandRepository;
    private final CoffeeProductRepository productRepository;
    private final OriginNodeRepository originNodeRepository;
    private final ProducerNodeRepository producerNodeRepository;
    private final FlavorNodeRepository flavorNodeRepository;

    private static final String CACHE_DIR = "src/main/resources/static/cache/";

    /**
     * Rebuild all cache files
     */
    public void rebuildAllCaches() {
        log.info("=== Starting cache rebuild ===");
        long startTime = System.currentTimeMillis();

        try {
            rebuildMapDataCache();
            rebuildFlavorDataCache();

            long duration = System.currentTimeMillis() - startTime;
            log.info("=== Cache rebuild completed in {}ms ===", duration);
        } catch (Exception e) {
            log.error("Failed to rebuild caches", e);
            throw new RuntimeException("Cache rebuild failed", e);
        }
    }

    /**
     * Rebuild map-data.json cache file
     */
    public void rebuildMapDataCache() {
        log.info("Rebuilding map-data.json...");

        try {
            // Build complete map data using optimized queries
            MapController.CompleteMapData mapData = buildCompleteMapData();

            // Write to JSON file
            writeJsonToFile(mapData, CACHE_DIR + "map-data.json");

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

            // Write to JSON file
            writeJsonToFile(flavorData, CACHE_DIR + "flavors-by-country.json");

            log.info("Successfully rebuilt flavors-by-country.json");
        } catch (Exception e) {
            log.error("Failed to rebuild flavors-by-country.json", e);
            throw new RuntimeException("Failed to rebuild flavor data cache", e);
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

        // Get all origins with coordinates
        List<MapController.OriginMapData> origins = ((List<OriginNode>) originNodeRepository.findAll()).stream()
                .filter(origin -> origin.getLatitude() != null && origin.getLongitude() != null)
                .filter(origin -> !isNonGeographicOrigin(origin.getCountry()))
                .map(origin -> new MapController.OriginMapData(
                        origin.getId(),
                        origin.getCountry(),
                        origin.getRegion(),
                        origin.getLatitude(),
                        origin.getLongitude(),
                        originProductCounts.getOrDefault(origin.getCountry(), 0L)
                ))
                .collect(Collectors.toList());

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
     */
    private List<MapController.ConnectionLine> buildConnections(
            List<MapController.BrandMapData> brands,
            List<MapController.OriginMapData> origins,
            List<MapController.ProducerMapData> producers,
            List<Long> brandIds) {

        List<MapController.ConnectionLine> connections = new ArrayList<>();

        // Fetch all products for approved brands in one query
        List<CoffeeProduct> allProducts = brandIds.isEmpty() ?
                Collections.emptyList() :
                productRepository.findByBrandId(brandIds.get(0)); // This needs improvement

        // Group products by brand
        Map<Long, List<CoffeeProduct>> productsByBrand = new HashMap<>();
        for (CoffeeProduct product : allProducts) {
            productsByBrand.computeIfAbsent(product.getBrand().getId(), k -> new ArrayList<>()).add(product);
        }

        // Build connections for each brand
        for (MapController.BrandMapData brand : brands) {
            List<CoffeeProduct> products = productRepository.findByBrandId(brand.getId());

            // Brand -> Origins
            Set<String> originCountries = products.stream()
                    .map(CoffeeProduct::getOrigin)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            for (String country : originCountries) {
                origins.stream()
                        .filter(origin -> origin.getCountry().equalsIgnoreCase(country))
                        .forEach(origin -> connections.add(new MapController.ConnectionLine(
                                "brand-origin",
                                brand.getId().toString(),
                                origin.getId(),
                                brand.getLatitude(),
                                brand.getLongitude(),
                                origin.getLatitude(),
                                origin.getLongitude()
                        )));
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
                        .forEach(producer -> {
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
     * Build flavor data
     */
    private List<CountryFlavorDTO> buildFlavorData() {
        List<Map<String, Object>> rawData = flavorNodeRepository.findTopFlavorsByCountry();
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
     * Check if origin is non-geographic
     */
    private boolean isNonGeographicOrigin(String country) {
        if (country == null) return true;
        String normalized = country.trim().toLowerCase();
        return normalized.equals("various") || normalized.equals("blend") ||
               normalized.equals("blended") || normalized.equals("mixed") ||
               normalized.equals("multiple") || normalized.equals("unknown") ||
               normalized.equals("n/a") || normalized.equals("na") ||
               normalized.isEmpty();
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
}
