package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.dto.CountryFlavorDTO;
import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.entity.CountryWeatherData;
import com.coffee.beansfinder.graph.node.BrandNode;
import com.coffee.beansfinder.graph.node.OriginNode;
import com.coffee.beansfinder.graph.node.ProducerNode;
import com.coffee.beansfinder.graph.repository.BrandNodeRepository;
import com.coffee.beansfinder.graph.repository.FlavorNodeRepository;
import com.coffee.beansfinder.graph.repository.OriginNodeRepository;
import com.coffee.beansfinder.graph.repository.ProducerNodeRepository;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.coffee.beansfinder.service.MapCacheService;
import com.coffee.beansfinder.service.OpenMeteoWeatherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/map")
@RequiredArgsConstructor
@Tag(name = "Map API", description = "Endpoints for geolocation and map visualization")
public class MapController {

    private final CoffeeBrandRepository brandRepository;
    private final CoffeeProductRepository productRepository;
    private final BrandNodeRepository brandNodeRepository;
    private final OriginNodeRepository originNodeRepository;
    private final ProducerNodeRepository producerNodeRepository;
    private final FlavorNodeRepository flavorNodeRepository;
    private final OpenMeteoWeatherService weatherService;
    private final MapCacheService mapCacheService;

    @GetMapping("/brands")
    @Operation(summary = "Get all approved brands with coordinates")
    public ResponseEntity<List<BrandMapData>> getAllBrandsWithCoordinates() {
        List<CoffeeBrand> brands = brandRepository.findAll().stream()
                .filter(brand -> brand.getApproved() != null && brand.getApproved()) // Only approved brands
                .filter(brand -> brand.getLatitude() != null && brand.getLongitude() != null)
                .collect(Collectors.toList());

        List<BrandMapData> mapData = brands.stream()
                .map(brand -> new BrandMapData(
                        brand.getId(),
                        brand.getName(),
                        brand.getCountry(),
                        brand.getLatitude(),
                        brand.getLongitude(),
                        brand.getWebsite(),
                        countProductsForBrand(brand.getId())
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(mapData);
    }

    @GetMapping("/origins")
    @Operation(summary = "Get all coffee origins with coordinates (excluding non-geographic origins)")
    public ResponseEntity<List<OriginMapData>> getAllOriginsWithCoordinates() {
        List<OriginNode> origins = (List<OriginNode>) originNodeRepository.findAll();

        List<OriginMapData> mapData = origins.stream()
                .filter(origin -> origin.getLatitude() != null && origin.getLongitude() != null)
                .filter(origin -> !isNonGeographicOrigin(origin.getCountry())) // Exclude "Various", "Blend", etc.
                .map(origin -> new OriginMapData(
                        origin.getId(),
                        origin.getCountry(),
                        origin.getRegion(),
                        origin.getLatitude(),
                        origin.getLongitude(),
                        countProductsFromOrigin(origin.getCountry())
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(mapData);
    }

    @GetMapping("/producers")
    @Operation(summary = "Get all producers with coordinates")
    public ResponseEntity<List<ProducerMapData>> getAllProducersWithCoordinates() {
        List<ProducerNode> producers = (List<ProducerNode>) producerNodeRepository.findAll();

        List<ProducerMapData> mapData = producers.stream()
                .filter(producer -> producer.getLatitude() != null && producer.getLongitude() != null)
                .map(producer -> new ProducerMapData(
                        producer.getId(),
                        producer.getName(),
                        producer.getCountry(),
                        producer.getRegion(),
                        producer.getCity(),
                        producer.getLatitude(),
                        producer.getLongitude()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(mapData);
    }

    @GetMapping("/connections/{brandId}")
    @Operation(summary = "Get connections for a brand (brand -> origins -> producers)")
    public ResponseEntity<BrandConnectionData> getBrandConnections(@PathVariable Long brandId) {
        CoffeeBrand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new RuntimeException("Brand not found: " + brandId));

        // Get all products for this brand
        List<CoffeeProduct> products = productRepository.findByBrandId(brandId);

        // Extract unique origins from products
        Set<String> originCountries = products.stream()
                .map(CoffeeProduct::getOrigin)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Get origin nodes with coordinates
        List<OriginMapData> origins = new ArrayList<>();
        for (String country : originCountries) {
            originNodeRepository.findById(country)
                    .filter(origin -> origin.getLatitude() != null)
                    .ifPresent(origin -> origins.add(new OriginMapData(
                            origin.getId(),
                            origin.getCountry(),
                            origin.getRegion(),
                            origin.getLatitude(),
                            origin.getLongitude(),
                            countProductsFromOrigin(origin.getCountry())
                    )));
        }

        // Extract producers from products (if available)
        Set<String> producerNames = products.stream()
                .map(CoffeeProduct::getProducer)
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toSet());

        List<ProducerMapData> producers = new ArrayList<>();
        for (String producerName : producerNames) {
            producerNodeRepository.findByNameContainingIgnoreCase(producerName)
                    .stream()
                    .filter(producer -> producer.getLatitude() != null)
                    .forEach(producer -> producers.add(new ProducerMapData(
                            producer.getId(),
                            producer.getName(),
                            producer.getCountry(),
                            producer.getRegion(),
                            producer.getCity(),
                            producer.getLatitude(),
                            producer.getLongitude()
                    )));
        }

        BrandConnectionData connectionData = new BrandConnectionData(
                new BrandMapData(
                        brand.getId(),
                        brand.getName(),
                        brand.getCountry(),
                        brand.getLatitude(),
                        brand.getLongitude(),
                        brand.getWebsite(),
                        products.size()
                ),
                origins,
                producers
        );

        return ResponseEntity.ok(connectionData);
    }

    @GetMapping("/data")
    @Operation(summary = "Get complete map dataset (all brands, origins, producers)")
    public ResponseEntity<CompleteMapData> getCompleteMapData() {
        // Get all APPROVED brands with coordinates
        List<BrandMapData> brands = brandRepository.findAll().stream()
                .filter(brand -> brand.getApproved() != null && brand.getApproved()) // Only approved brands
                .filter(brand -> brand.getLatitude() != null && brand.getLongitude() != null)
                .map(brand -> new BrandMapData(
                        brand.getId(),
                        brand.getName(),
                        brand.getCountry(),
                        brand.getLatitude(),
                        brand.getLongitude(),
                        brand.getWebsite(),
                        countProductsForBrand(brand.getId())
                ))
                .collect(Collectors.toList());

        // Get all origins with coordinates, excluding non-geographic origins
        List<OriginMapData> origins = ((List<OriginNode>) originNodeRepository.findAll()).stream()
                .filter(origin -> origin.getLatitude() != null && origin.getLongitude() != null)
                .filter(origin -> !isNonGeographicOrigin(origin.getCountry())) // Exclude "Various", "Blend", etc.
                .map(origin -> new OriginMapData(
                        origin.getId(),
                        origin.getCountry(),
                        origin.getRegion(),
                        origin.getLatitude(),
                        origin.getLongitude(),
                        countProductsFromOrigin(origin.getCountry())
                ))
                .collect(Collectors.toList());

        // Get all producers with coordinates
        List<ProducerMapData> producers = ((List<ProducerNode>) producerNodeRepository.findAll()).stream()
                .filter(producer -> producer.getLatitude() != null && producer.getLongitude() != null)
                .map(producer -> new ProducerMapData(
                        producer.getId(),
                        producer.getName(),
                        producer.getCountry(),
                        producer.getRegion(),
                        producer.getCity(),
                        producer.getLatitude(),
                        producer.getLongitude()
                ))
                .collect(Collectors.toList());

        // Build connections: for each brand, find its origins and producers
        List<ConnectionLine> connections = new ArrayList<>();
        for (BrandMapData brand : brands) {
            List<CoffeeProduct> products = productRepository.findByBrandId(brand.getId());

            // Brand -> Origins
            Set<String> originCountries = products.stream()
                    .map(CoffeeProduct::getOrigin)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            for (String country : originCountries) {
                origins.stream()
                        .filter(origin -> origin.getCountry().equalsIgnoreCase(country))
                        .forEach(origin -> connections.add(new ConnectionLine(
                                "brand-origin",
                                brand.getId().toString(),
                                origin.getId(),
                                brand.getLatitude(),
                                brand.getLongitude(),
                                origin.getLatitude(),
                                origin.getLongitude()
                        )));
            }

            // Origins -> Producers (if producers exist)
            Set<String> producerNames = products.stream()
                    .map(CoffeeProduct::getProducer)
                    .filter(Objects::nonNull)
                    .filter(name -> !name.isBlank())
                    .collect(Collectors.toSet());

            for (String producerName : producerNames) {
                producers.stream()
                        .filter(producer -> producer.getName().toLowerCase().contains(producerName.toLowerCase()))
                        .forEach(producer -> {
                            // Find matching origin for this producer
                            origins.stream()
                                    .filter(origin -> origin.getCountry().equalsIgnoreCase(producer.getCountry()))
                                    .findFirst()
                                    .ifPresent(origin -> connections.add(new ConnectionLine(
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

        CompleteMapData mapData = new CompleteMapData(brands, origins, producers, connections);
        return ResponseEntity.ok(mapData);
    }

    // Helper methods
    private long countProductsForBrand(Long brandId) {
        return productRepository.countByBrandId(brandId);
    }

    private long countProductsFromOrigin(String country) {
        return productRepository.findAll().stream()
                .filter(p -> country.equalsIgnoreCase(p.getOrigin()))
                .count();
    }

    // DTOs
    @Data
    @AllArgsConstructor
    public static class BrandMapData {
        private Long id;
        private String name;
        private String country;
        private Double latitude;
        private Double longitude;
        private String website;
        private long productCount;
    }

    @Data
    @AllArgsConstructor
    public static class OriginMapData {
        private String id;
        private String country;
        private String region;
        private Double latitude;
        private Double longitude;
        private long productCount;
    }

    @Data
    @AllArgsConstructor
    public static class ProducerMapData {
        private String id;
        private String name;
        private String country;
        private String region;
        private String city;
        private Double latitude;
        private Double longitude;
    }

    @Data
    @AllArgsConstructor
    public static class BrandConnectionData {
        private BrandMapData brand;
        private List<OriginMapData> origins;
        private List<ProducerMapData> producers;
    }

    @Data
    @AllArgsConstructor
    public static class ConnectionLine {
        private String type; // "brand-origin" or "origin-producer"
        private String fromId;
        private String toId;
        private Double fromLat;
        private Double fromLon;
        private Double toLat;
        private Double toLon;
    }

    @Data
    @AllArgsConstructor
    public static class CompleteMapData {
        private List<BrandMapData> brands;
        private List<OriginMapData> origins;
        private List<ProducerMapData> producers;
        private List<ConnectionLine> connections;
    }

    // Flavor API endpoints
    @GetMapping("/flavors-by-country")
    @Operation(summary = "Get top 5 flavors aggregated by country for map labels")
    public ResponseEntity<List<CountryFlavorDTO>> getFlavorsByCountry() {
        List<Map<String, Object>> rawData = flavorNodeRepository.findTopFlavorsByCountry();

        List<CountryFlavorDTO> result = new ArrayList<>();

        for (Map<String, Object> dataMap : rawData) {
            try {
                // The data is wrapped in a "data" key by the RETURN {country: ..., topFlavors: ...} as data
                Map<String, Object> innerData = (Map<String, Object>) dataMap.get("data");

                if (innerData == null) {
                    // Try direct access if not wrapped
                    innerData = dataMap;
                }

                String country = (String) innerData.get("country");
                List<Map<String, Object>> topFlavorsRaw = (List<Map<String, Object>>) innerData.get("topFlavors");

                if (country == null || topFlavorsRaw == null) {
                    continue; // Skip invalid entries
                }

                List<CountryFlavorDTO.FlavorInfo> topFlavors = new ArrayList<>();
                for (Map<String, Object> flavorMap : topFlavorsRaw) {
                    String flavor = (String) flavorMap.get("flavor");
                    String category = (String) flavorMap.get("category");
                    Long productCount = ((Number) flavorMap.get("productCount")).longValue();
                    Double percentage = ((Number) flavorMap.get("percentage")).doubleValue();

                    topFlavors.add(new CountryFlavorDTO.FlavorInfo(flavor, category, productCount, percentage));
                }

                // Get country code for the country name
                String countryCode = getCountryCode(country);

                result.add(new CountryFlavorDTO(countryCode, country, topFlavors));
            } catch (Exception e) {
                // Log and skip invalid entries
                System.err.println("Error processing flavor data entry: " + e.getMessage());
            }
        }

        return ResponseEntity.ok(result);
    }

    // Weather API endpoints
    @GetMapping("/weather/{countryCode}")
    @Operation(summary = "Get historical weather data for a country (monthly trends by year)")
    public ResponseEntity<WeatherDataResponse> getWeatherData(@PathVariable String countryCode) {
        List<CountryWeatherData> weatherData = weatherService.getWeatherData(countryCode.toUpperCase());

        if (weatherData.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Transform data into year-over-year monthly format
        WeatherDataResponse response = transformToYearlyOverlap(weatherData);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/weather/fetch/{countryCode}")
    @Operation(summary = "Fetch and cache weather data for a country from Open-Meteo API")
    public ResponseEntity<Map<String, Object>> fetchWeatherData(
            @PathVariable String countryCode,
            @RequestParam(required = false) String countryName,
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        try {
            String normalizedCode = countryCode.toUpperCase();
            String name = countryName != null ? countryName : normalizedCode;

            List<CountryWeatherData> weatherData = weatherService.fetchAndCacheWeatherData(
                    name, normalizedCode, latitude, longitude, force
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("countryCode", normalizedCode);
            response.put("countryName", name);
            response.put("recordsCreated", weatherData.size());
            response.put("message", "Weather data fetched and cached successfully");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    @PostMapping("/weather/batch-fetch")
    @Operation(summary = "Batch fetch weather data for all coffee origins from database")
    public ResponseEntity<Map<String, Object>> batchFetchWeatherData(
            @RequestParam(defaultValue = "false") boolean force
    ) {
        try {
            // Get all unique origins from Neo4j
            List<OriginNode> origins = (List<OriginNode>) originNodeRepository.findAll();

            int successCount = 0;
            int skipCount = 0;
            int errorCount = 0;
            List<String> errors = new ArrayList<>();
            List<String> processed = new ArrayList<>();

            for (OriginNode origin : origins) {
                // Skip if no coordinates
                if (origin.getLatitude() == null || origin.getLongitude() == null) {
                    skipCount++;
                    continue;
                }

                // Get country code (use first 2 letters of country as fallback)
                String countryCode = getCountryCode(origin.getCountry());
                if (countryCode == null || countryCode.length() < 2) {
                    skipCount++;
                    continue;
                }

                // Skip if already exists and not forcing
                if (!force && weatherService.hasWeatherData(countryCode)) {
                    skipCount++;
                    continue;
                }

                try {
                    weatherService.fetchAndCacheWeatherData(
                            origin.getCountry(),
                            countryCode,
                            origin.getLatitude(),
                            origin.getLongitude(),
                            force
                    );
                    successCount++;
                    processed.add(origin.getCountry() + " (" + countryCode + ")");
                } catch (Exception e) {
                    errorCount++;
                    errors.add(origin.getCountry() + ": " + e.getMessage());
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("totalOrigins", origins.size());
            response.put("successCount", successCount);
            response.put("skipCount", skipCount);
            response.put("errorCount", errorCount);
            response.put("processed", processed);
            if (!errors.isEmpty()) {
                response.put("errors", errors);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    // Cache Management API
    @PostMapping("/rebuild-cache")
    @Operation(summary = "Manually rebuild map cache files (map-data.json, flavors-by-country.json)")
    public ResponseEntity<Map<String, Object>> rebuildCache() {
        try {
            long startTime = System.currentTimeMillis();
            mapCacheService.rebuildAllCaches();
            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Cache rebuilt successfully");
            response.put("durationMs", duration);
            response.put("cacheLocation", "src/main/resources/static/cache/");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * Check if an origin is non-geographic (blend, various, etc.)
     */
    private boolean isNonGeographicOrigin(String country) {
        if (country == null) return true;

        String normalized = country.trim().toLowerCase();

        // List of non-geographic origin names to exclude from map
        return normalized.equals("various") ||
               normalized.equals("blend") ||
               normalized.equals("blended") ||
               normalized.equals("mixed") ||
               normalized.equals("multiple") ||
               normalized.equals("unknown") ||
               normalized.equals("n/a") ||
               normalized.equals("na") ||
               normalized.isEmpty();
    }

    /**
     * Get country code from country name (simple mapping for common coffee countries)
     */
    private String getCountryCode(String countryName) {
        if (countryName == null) return null;

        String normalized = countryName.trim().toUpperCase();

        // Common coffee-producing countries
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
     * Transform weather data into year-over-year monthly comparison format.
     * Format: For each metric, create a map of year -> [12 monthly values]
     */
    private WeatherDataResponse transformToYearlyOverlap(List<CountryWeatherData> data) {
        if (data.isEmpty()) {
            return new WeatherDataResponse();
        }

        String countryCode = data.get(0).getCountryCode();
        String countryName = data.get(0).getCountryName();

        // Get unique years and sort
        List<Integer> years = data.stream()
                .map(CountryWeatherData::getYear)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // Month labels
        String[] monthLabels = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

        // Create maps: year -> [12 monthly values]
        Map<Integer, List<Double>> temperatureByYear = new LinkedHashMap<>();
        Map<Integer, List<Double>> rainfallByYear = new LinkedHashMap<>();
        Map<Integer, List<Double>> soilMoistureByYear = new LinkedHashMap<>();
        Map<Integer, List<Double>> solarRadiationByYear = new LinkedHashMap<>();

        // Initialize arrays for each year
        for (Integer year : years) {
            temperatureByYear.put(year, new ArrayList<>(Collections.nCopies(12, null)));
            rainfallByYear.put(year, new ArrayList<>(Collections.nCopies(12, null)));
            soilMoistureByYear.put(year, new ArrayList<>(Collections.nCopies(12, null)));
            solarRadiationByYear.put(year, new ArrayList<>(Collections.nCopies(12, null)));
        }

        // Fill in values
        for (CountryWeatherData record : data) {
            int year = record.getYear();
            int monthIndex = record.getMonth() - 1; // Convert 1-12 to 0-11

            if (monthIndex >= 0 && monthIndex < 12) {
                temperatureByYear.get(year).set(monthIndex, record.getAvgTemperature());
                rainfallByYear.get(year).set(monthIndex, record.getTotalRainfall());
                soilMoistureByYear.get(year).set(monthIndex, record.getAvgSoilMoisture());
                solarRadiationByYear.get(year).set(monthIndex, record.getAvgSolarRadiation());
            }
        }

        return new WeatherDataResponse(
                countryCode,
                countryName,
                years,
                Arrays.asList(monthLabels),
                temperatureByYear,
                rainfallByYear,
                soilMoistureByYear,
                solarRadiationByYear
        );
    }

    @Data
    @AllArgsConstructor
    public static class WeatherDataResponse {
        private String countryCode;
        private String countryName;
        private List<Integer> years;
        private List<String> months;
        private Map<Integer, List<Double>> temperatureByYear; // Year -> [12 monthly temps in °C]
        private Map<Integer, List<Double>> rainfallByYear;     // Year -> [12 monthly rainfall in mm]
        private Map<Integer, List<Double>> soilMoistureByYear; // Year -> [12 monthly soil moisture 0-1]
        private Map<Integer, List<Double>> solarRadiationByYear; // Year -> [12 monthly solar radiation W/m²]

        public WeatherDataResponse() {
            this.years = new ArrayList<>();
            this.months = new ArrayList<>();
            this.temperatureByYear = new LinkedHashMap<>();
            this.rainfallByYear = new LinkedHashMap<>();
            this.soilMoistureByYear = new LinkedHashMap<>();
            this.solarRadiationByYear = new LinkedHashMap<>();
        }
    }
}
