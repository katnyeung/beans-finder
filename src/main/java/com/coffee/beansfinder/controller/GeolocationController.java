package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.entity.LocationCoordinates;
import com.coffee.beansfinder.graph.node.OriginNode;
import com.coffee.beansfinder.graph.repository.OriginNodeRepository;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.service.KnowledgeGraphService;
import com.coffee.beansfinder.service.NominatimGeolocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/geolocation")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Geolocation API", description = "Endpoints for geocoding brands and origins")
public class GeolocationController {

    private final NominatimGeolocationService geolocationService;
    private final CoffeeBrandRepository brandRepository;
    private final OriginNodeRepository originNodeRepository;
    private final KnowledgeGraphService knowledgeGraphService;
    private final com.coffee.beansfinder.repository.CoffeeProductRepository productRepository;
    private final com.coffee.beansfinder.service.OpenAIService openAIService;

    @PostMapping("/geocode-brand/{brandId}")
    @Operation(summary = "Geocode a brand by ID using address → city → country fallback")
    public ResponseEntity<Map<String, Object>> geocodeBrand(@PathVariable Long brandId) {
        CoffeeBrand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new RuntimeException("Brand not found: " + brandId));

        if (brand.getCountry() == null || brand.getCountry().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Brand has no country set"));
        }

        // Geocode with fallback: address → city → country
        LocationCoordinates coords = null;
        String locationUsed = null;

        // Try address first (most precise)
        if (brand.getAddress() != null && !brand.getAddress().isBlank()) {
            log.info("Geocoding brand {} using address: {}", brand.getName(), brand.getAddress());
            coords = geolocationService.geocode(brand.getAddress(), brand.getCountry(), null);
            locationUsed = "address: " + brand.getAddress();
        }

        // Fallback to city
        if (coords == null && brand.getCity() != null && !brand.getCity().isBlank()) {
            log.info("Geocoding brand {} using city: {}", brand.getName(), brand.getCity());
            coords = geolocationService.geocode(brand.getCity(), brand.getCountry(), null);
            locationUsed = "city: " + brand.getCity();
        }

        // Last resort: country center
        if (coords == null) {
            log.info("Geocoding brand {} using country: {}", brand.getName(), brand.getCountry());
            coords = geolocationService.geocodeCountry(brand.getCountry());
            locationUsed = "country: " + brand.getCountry();
        }

        if (coords != null) {
            brand.setLatitude(coords.getLatitude());
            brand.setLongitude(coords.getLongitude());
            brand.setCoordinatesValidated(true);
            brandRepository.save(brand);

            // Re-sync to Neo4j to update BrandNode coordinates
            brand.getProducts().forEach(product -> {
                try {
                    knowledgeGraphService.syncProductToGraph(product);
                } catch (Exception e) {
                    log.warn("Failed to sync product to graph: {}", product.getId(), e);
                }
            });

            Map<String, Object> response = new HashMap<>();
            response.put("brandId", brand.getId());
            response.put("brandName", brand.getName());
            response.put("locationUsed", locationUsed);
            response.put("latitude", coords.getLatitude());
            response.put("longitude", coords.getLongitude());
            response.put("source", coords.getSource());

            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Failed to geocode brand: " + brand.getName()));
        }
    }

    @PostMapping("/geocode-origin")
    @Operation(summary = "Geocode an origin (country + optional region)")
    public ResponseEntity<Map<String, Object>> geocodeOrigin(
            @RequestParam String country,
            @RequestParam(required = false) String region) {

        log.info("Geocoding origin: {} (region: {})", country, region);

        LocationCoordinates coords = geolocationService.geocode(
                region != null ? region + ", " + country : country,
                country,
                region
        );

        if (coords != null) {
            // Update Neo4j OriginNode if exists
            String originId = OriginNode.generateId(country, region);
            originNodeRepository.findById(originId).ifPresent(originNode -> {
                originNode.setLatitude(coords.getLatitude());
                originNode.setLongitude(coords.getLongitude());
                originNode.setBoundingBox(coords.getBoundingBox());
                originNodeRepository.save(originNode);
                log.info("Updated OriginNode {} with coordinates", originId);
            });

            Map<String, Object> response = new HashMap<>();
            response.put("country", country);
            response.put("region", region);
            response.put("originId", originId);
            response.put("latitude", coords.getLatitude());
            response.put("longitude", coords.getLongitude());
            response.put("source", coords.getSource());

            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Failed to geocode origin: " + country +
                            (region != null ? ", " + region : "")));
        }
    }

    @PostMapping("/batch-geocode-brands")
    @Operation(summary = "Batch geocode all brands without coordinates")
    public ResponseEntity<Map<String, Object>> batchGeocodeBrands(
            @RequestParam(defaultValue = "false") boolean force) {

        var brands = brandRepository.findAll();
        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        log.info("Starting batch geocoding for {} brands (force={})", brands.size(), force);

        for (CoffeeBrand brand : brands) {
            // Skip if already geocoded (unless force=true)
            if (!force && brand.getLatitude() != null && brand.getLongitude() != null) {
                skipCount++;
                continue;
            }

            if (brand.getCountry() == null || brand.getCountry().isBlank()) {
                errorCount++;
                continue;
            }

            try {
                LocationCoordinates coords = geolocationService.geocodeCountry(brand.getCountry());
                if (coords != null) {
                    brand.setLatitude(coords.getLatitude());
                    brand.setLongitude(coords.getLongitude());
                    brand.setCoordinatesValidated(true);
                    brandRepository.save(brand);
                    successCount++;
                    log.info("Geocoded brand: {} -> ({}, {})",
                            brand.getName(), coords.getLatitude(), coords.getLongitude());
                } else {
                    errorCount++;
                }
            } catch (Exception e) {
                log.error("Error geocoding brand: {}", brand.getName(), e);
                errorCount++;
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("totalBrands", brands.size());
        response.put("success", successCount);
        response.put("skipped", skipCount);
        response.put("errors", errorCount);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/batch-geocode-origins")
    @Operation(summary = "Batch geocode all origin nodes without coordinates")
    public ResponseEntity<Map<String, Object>> batchGeocodeOrigins(
            @RequestParam(defaultValue = "false") boolean force) {

        var origins = (java.util.List<OriginNode>) originNodeRepository.findAll();
        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        log.info("Starting batch geocoding for {} origins (force={})", origins.size(), force);

        for (OriginNode origin : origins) {
            // Skip if already geocoded (unless force=true)
            if (!force && origin.getLatitude() != null && origin.getLongitude() != null) {
                skipCount++;
                continue;
            }

            if (origin.getCountry() == null || origin.getCountry().isBlank()) {
                errorCount++;
                continue;
            }

            try {
                LocationCoordinates coords = geolocationService.geocode(
                        origin.getRegion() != null ? origin.getRegion() + ", " + origin.getCountry() : origin.getCountry(),
                        origin.getCountry(),
                        origin.getRegion()
                );

                if (coords != null) {
                    origin.setLatitude(coords.getLatitude());
                    origin.setLongitude(coords.getLongitude());
                    origin.setBoundingBox(coords.getBoundingBox());
                    originNodeRepository.save(origin);
                    successCount++;
                    log.info("Geocoded origin: {} -> ({}, {})",
                            origin.getId(), coords.getLatitude(), coords.getLongitude());
                } else {
                    errorCount++;
                }
            } catch (Exception e) {
                log.error("Error geocoding origin: {}", origin.getId(), e);
                errorCount++;
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("totalOrigins", origins.size());
        response.put("success", successCount);
        response.put("skipped", skipCount);
        response.put("errors", errorCount);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/seed-origins-from-products")
    @Operation(summary = "Extract origins from existing products and geocode them using AI in batch")
    public ResponseEntity<Map<String, Object>> seedOriginsFromProducts() {

        log.info("Extracting origins from existing products...");

        try {
            // Get all unique origins from products
            var products = productRepository.findAll();
            java.util.Set<String> uniqueOrigins = new java.util.HashSet<>();

            for (var product : products) {
                if (product.getOrigin() != null && !product.getOrigin().isBlank()) {
                    // Split multi-value origins (e.g., "Ethiopia / Colombia")
                    String[] origins = product.getOrigin().split("[/,]");
                    for (String origin : origins) {
                        String cleaned = origin.trim();
                        if (!cleaned.isEmpty()) {
                            uniqueOrigins.add(cleaned);
                        }
                    }
                }
            }

            log.info("Found {} unique origins in database", uniqueOrigins.size());

            if (uniqueOrigins.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "message", "No origins found in products",
                        "newlySeeded", 0,
                        "alreadyExisted", 0,
                        "total", 0
                ));
            }

            // Process in smaller batches to avoid token limits
            List<OriginLocationDto> allGeocoded = new ArrayList<>();
            List<String> originsList = new ArrayList<>(uniqueOrigins);
            int batchSize = 10; // Process 10 origins at a time

            for (int i = 0; i < originsList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, originsList.size());
                List<String> batch = originsList.subList(i, end);

                log.info("Processing batch {}/{}: {} origins", (i/batchSize + 1), (originsList.size() + batchSize - 1)/batchSize, batch.size());

                String prompt = String.format("""
                        For each coffee origin location below, return ONLY latitude and longitude.

                        Locations: %s

                        Return as JSON array: [{"name":"Ethiopia","lat":9.145,"lon":40.49,"country":"Ethiopia","region":null}, ...]

                        Rules:
                        - Parse location to extract country and region if specified
                        - Use center coordinates for countries, specific coordinates for regions
                        - If location is invalid/blend, use lat:0, lon:0
                        - Return ONLY the JSON array, no markdown
                        """, String.join(", ", batch));

                String response = openAIService.callOpenAI(prompt);

                // Parse batch response
                String cleanedResponse = response.trim();
                if (cleanedResponse.startsWith("```json")) cleanedResponse = cleanedResponse.substring(7);
                if (cleanedResponse.startsWith("```")) cleanedResponse = cleanedResponse.substring(3);
                if (cleanedResponse.endsWith("```")) cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
                cleanedResponse = cleanedResponse.trim();

                log.debug("Batch response: {}", cleanedResponse.substring(0, Math.min(300, cleanedResponse.length())));

                // Parse simplified format
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                List<Map<String, Object>> batchResults;

                // Handle both array and object responses
                if (cleanedResponse.startsWith("[")) {
                    // Direct array
                    batchResults = mapper.readValue(
                            cleanedResponse,
                            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}
                    );
                } else if (cleanedResponse.startsWith("{")) {
                    // Wrapped in object, extract array
                    com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(cleanedResponse);

                    // Look for array field
                    com.fasterxml.jackson.databind.JsonNode arrayNode = null;
                    java.util.Iterator<String> fieldNames = rootNode.fieldNames();
                    while (fieldNames.hasNext()) {
                        String fieldName = fieldNames.next();
                        com.fasterxml.jackson.databind.JsonNode field = rootNode.get(fieldName);
                        if (field.isArray()) {
                            arrayNode = field;
                            log.debug("Found array in field: {}", fieldName);
                            break;
                        }
                    }

                    if (arrayNode != null) {
                        batchResults = mapper.readValue(
                                arrayNode.toString(),
                                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}
                        );
                    } else {
                        throw new RuntimeException("OpenAI returned object without array. Response: " + cleanedResponse.substring(0, Math.min(200, cleanedResponse.length())));
                    }
                } else {
                    throw new RuntimeException("Unexpected response format: " + cleanedResponse.substring(0, Math.min(100, cleanedResponse.length())));
                }

                // Convert to OriginLocationDto
                for (Map<String, Object> result : batchResults) {
                    OriginLocationDto dto = new OriginLocationDto();
                    dto.locationName = (String) result.get("name");
                    dto.country = (String) result.get("country");
                    dto.region = (String) result.get("region");

                    Object latObj = result.get("lat");
                    Object lonObj = result.get("lon");
                    dto.latitude = latObj instanceof Number ? ((Number) latObj).doubleValue() : 0.0;
                    dto.longitude = lonObj instanceof Number ? ((Number) lonObj).doubleValue() : 0.0;

                    allGeocoded.add(dto);
                }
            }

            log.info("Successfully geocoded {} origins in {} batches", allGeocoded.size(), (originsList.size() + batchSize - 1)/batchSize);

            // Save each location to cache
            int newCount = 0;
            int existingCount = 0;
            int skippedCount = 0;

            for (OriginLocationDto origin : allGeocoded) {
                // Skip if coordinates are null (e.g., "Blend (Global)")
                if (origin.country == null || origin.latitude == 0.0 || origin.longitude == 0.0) {
                    log.info("Skipping origin with null/zero coordinates: {}", origin.locationName);
                    skippedCount++;
                    continue;
                }

                try {
                    boolean isNew = geolocationService.saveCachedLocationAndReturnStatus(
                            origin.locationName,
                            origin.country,
                            origin.region,
                            origin.latitude,
                            origin.longitude,
                            null
                    );

                    if (isNew) {
                        newCount++;
                    } else {
                        existingCount++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to save origin {}: {}", origin.locationName, e.getMessage());
                    skippedCount++;
                }
            }

            log.info("Geocoded and cached {} new origins, {} already existed", newCount, existingCount);

            return ResponseEntity.ok(Map.of(
                    "message", "Extracted origins from products and geocoded using AI",
                    "uniqueOriginsFound", uniqueOrigins.size(),
                    "newlySeeded", newCount,
                    "alreadyExisted", existingCount,
                    "skipped", skippedCount,
                    "total", newCount + existingCount
            ));

        } catch (Exception e) {
            log.error("Failed to geocode origins from products", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to geocode origins: " + e.getMessage()
            ));
        }
    }

    // Removed hardcoded seedCoffeeOrigins and seedLocation methods
    // Use /api/geolocation/seed-origins-from-products instead to dynamically geocode from actual product data

    // DTO for origin location data from AI
    public static class OriginLocationDto {
        public String locationName;
        public String country;
        public String region;
        public double latitude;
        public double longitude;
    }
}
