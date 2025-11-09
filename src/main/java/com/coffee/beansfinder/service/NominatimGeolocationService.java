package com.coffee.beansfinder.service;

import com.coffee.beansfinder.entity.LocationCoordinates;
import com.coffee.beansfinder.repository.LocationCoordinatesRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Optional;

@Service
public class NominatimGeolocationService {

    private static final Logger logger = LoggerFactory.getLogger(NominatimGeolocationService.class);
    private static final String NOMINATIM_API_URL = "https://nominatim.openstreetmap.org/search";
    private static final long RATE_LIMIT_MS = 1000; // 1 request per second
    private static Instant lastRequestTime = Instant.EPOCH;

    @Autowired
    private LocationCoordinatesRepository locationCoordinatesRepository;

    @Autowired
    private OpenAIService openAIService;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public NominatimGeolocationService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Geocode a location (country + optional region).
     * Checks cache first, then calls Nominatim API if not found.
     *
     * @param locationName Display name for the location
     * @param country Country name
     * @param region Optional region/state/province
     * @return LocationCoordinates object with lat/lon populated
     */
    public LocationCoordinates geocode(String locationName, String country, String region) {
        if (country == null || country.isBlank()) {
            logger.warn("Cannot geocode: country is null or blank");
            return null;
        }

        logger.debug("geocode() called with: locationName='{}', country='{}', region='{}'",
                locationName, country, region);

        // Check cache first
        Optional<LocationCoordinates> cached = locationCoordinatesRepository
                .findByLocationNameAndCountryAndRegion(locationName, country, region);

        if (cached.isPresent()) {
            logger.info("Found cached coordinates for: {} ({}, {})", locationName, country, region);
            return cached.get();
        }

        // Call Nominatim API with rate limiting
        return geocodeFromNominatim(locationName, country, region);
    }

    /**
     * Geocode country only (no region).
     */
    public LocationCoordinates geocodeCountry(String country) {
        if (country == null || country.isBlank()) {
            logger.warn("Cannot geocode: country is null or blank");
            return null;
        }

        // Check cache first
        Optional<LocationCoordinates> cached = locationCoordinatesRepository.findByCountryOnly(country);

        if (cached.isPresent()) {
            logger.info("Found cached coordinates for country: {}", country);
            return cached.get();
        }

        // Call Nominatim API
        return geocodeFromNominatim(country, country, null);
    }

    /**
     * Call Nominatim API with rate limiting (1 req/sec).
     */
    private LocationCoordinates geocodeFromNominatim(String locationName, String country, String region) {
        // Rate limiting: ensure at least 1 second between requests
        synchronized (NominatimGeolocationService.class) {
            long timeSinceLastRequest = Instant.now().toEpochMilli() - lastRequestTime.toEpochMilli();
            if (timeSinceLastRequest < RATE_LIMIT_MS) {
                try {
                    long sleepTime = RATE_LIMIT_MS - timeSinceLastRequest;
                    logger.debug("Rate limiting: sleeping for {} ms", sleepTime);
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Rate limiting interrupted", e);
                    return null;
                }
            }
            lastRequestTime = Instant.now();
        }

        // Build search query - use locationName if it's different from country (full address/city)
        String query = buildSearchQuery(locationName, country, region);
        logger.info("Geocoding via Nominatim: {}", query);

        try {
            // Build URL with query parameters
            // Use 'countrycodes' parameter to constrain search to specific country for better accuracy
            String countryCode = mapCountryToCode(country);

            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(NOMINATIM_API_URL)
                    .queryParam("q", query)
                    .queryParam("format", "json")
                    .queryParam("limit", "1")
                    .queryParam("addressdetails", "1");

            // Add country code filter if available (improves accuracy)
            if (countryCode != null) {
                builder.queryParam("countrycodes", countryCode);
            }

            String url = builder.build().toUriString();

            // Add User-Agent header (required by Nominatim)
            restTemplate.getInterceptors().add((request, body, execution) -> {
                request.getHeaders().add("User-Agent", "CoffeeBeansFinderApp/1.0 (contact@example.com)");
                return execution.execute(request, body);
            });

            String response = restTemplate.getForObject(url, String.class);

            // Parse response
            JsonNode results = objectMapper.readTree(response);
            if (results.isArray() && results.size() > 0) {
                JsonNode firstResult = results.get(0);
                double lat = firstResult.get("lat").asDouble();
                double lon = firstResult.get("lon").asDouble();

                // Extract bounding box if available
                String boundingBox = null;
                if (firstResult.has("boundingbox")) {
                    JsonNode bbox = firstResult.get("boundingbox");
                    boundingBox = String.format("{\"minLat\": %s, \"maxLat\": %s, \"minLon\": %s, \"maxLon\": %s}",
                            bbox.get(0).asText(), bbox.get(1).asText(),
                            bbox.get(2).asText(), bbox.get(3).asText());
                }

                // Save to cache
                LocationCoordinates location = new LocationCoordinates(locationName, country, region);
                location.setLatitude(lat);
                location.setLongitude(lon);
                location.setBoundingBox(boundingBox);
                location.setSource("nominatim");

                LocationCoordinates saved = locationCoordinatesRepository.save(location);
                logger.info("Geocoded and cached: {} -> ({}, {})", locationName, lat, lon);
                return saved;
            } else {
                logger.warn("No results from Nominatim for: {}", query);

                // FALLBACK: Try OpenAI for geocoding
                logger.info("Attempting LLM fallback geocoding for: {}", query);
                return geocodeWithLLM(locationName, country, region, query);
            }

        } catch (Exception e) {
            logger.error("Error geocoding with Nominatim: {}", query, e);

            // FALLBACK: Try OpenAI on error
            logger.info("Nominatim error, attempting LLM fallback for: {}", query);
            try {
                return geocodeWithLLM(locationName, country, region, query);
            } catch (Exception llmException) {
                logger.error("LLM fallback also failed: {}", llmException.getMessage());
                return null;
            }
        }
    }

    /**
     * Fallback geocoding using OpenAI when Nominatim fails.
     * Uses GPT-4o-mini to get coordinates for specific addresses.
     */
    private LocationCoordinates geocodeWithLLM(String locationName, String country, String region, String query) {
        try {
            String prompt = String.format("""
                    Return ONLY the latitude and longitude coordinates for this address in JSON format.

                    Address: %s

                    Return format (no markdown, no explanations):
                    {"lat": 51.4545, "lon": -2.5879}

                    Rules:
                    - Return approximate center coordinates if exact address not found
                    - Use the most likely location based on postcode/city
                    - If address is invalid/not found, return the city/region center coordinates
                    """, query);

            String response = openAIService.callOpenAI(prompt);

            // Clean response
            String cleaned = response.trim();
            if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
            if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.trim();

            // Parse JSON response
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> coords = mapper.readValue(
                    cleaned,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}
            );

            double lat = ((Number) coords.get("lat")).doubleValue();
            double lon = ((Number) coords.get("lon")).doubleValue();

            // Validate coordinates are reasonable
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                logger.error("LLM returned invalid coordinates: lat={}, lon={}", lat, lon);
                return null;
            }

            // Save to cache with source='llm'
            LocationCoordinates location = new LocationCoordinates(locationName, country, region);
            location.setLatitude(lat);
            location.setLongitude(lon);
            location.setBoundingBox(null); // No bounding box from LLM
            location.setSource("llm");

            LocationCoordinates saved = locationCoordinatesRepository.save(location);
            logger.info("LLM geocoded and cached: {} -> ({}, {}) [source: llm]", locationName, lat, lon);
            return saved;

        } catch (Exception e) {
            logger.error("Failed to geocode with LLM: {}", query, e);
            return null;
        }
    }

    /**
     * Build search query for Nominatim.
     * Priority: locationName (full address/city) > region > country
     */
    private String buildSearchQuery(String locationName, String country, String region) {
        // If locationName is different from country, use it (full address or city)
        if (locationName != null && !locationName.equals(country)) {
            // If it already contains the country, use as-is
            if (locationName.toLowerCase().contains(country.toLowerCase())) {
                return locationName;
            }
            // Otherwise append country for better accuracy
            return locationName + ", " + country;
        }

        // Fallback: region + country
        if (region != null && !region.isBlank()) {
            return region + ", " + country;
        }

        // Last resort: just country
        return country;
    }

    /**
     * Manually save coordinates to cache (for seeded data).
     * Checks if location already exists to avoid duplicates.
     * Returns true if newly created, false if already existed.
     */
    public boolean saveCachedLocationAndReturnStatus(String locationName, String country, String region,
                                                      double latitude, double longitude, String boundingBox) {
        // Check if already exists
        Optional<LocationCoordinates> existing = locationCoordinatesRepository
                .findByLocationNameAndCountryAndRegion(locationName, country, region);

        if (existing.isPresent()) {
            logger.debug("Location already seeded: {} ({}, {})", locationName, country, region);
            return false; // Already existed
        }

        // Create new entry
        LocationCoordinates location = new LocationCoordinates(locationName, country, region);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setBoundingBox(boundingBox);
        location.setSource("seeded");

        locationCoordinatesRepository.save(location);
        logger.info("Seeded new location: {} -> ({}, {})", locationName, latitude, longitude);
        return true; // Newly created
    }

    /**
     * Manually save coordinates to cache (for seeded data).
     * Legacy method for backwards compatibility.
     */
    public LocationCoordinates saveCachedLocation(String locationName, String country, String region,
                                                   double latitude, double longitude, String boundingBox) {
        saveCachedLocationAndReturnStatus(locationName, country, region, latitude, longitude, boundingBox);
        return locationCoordinatesRepository
                .findByLocationNameAndCountryAndRegion(locationName, country, region)
                .orElse(null);
    }

    /**
     * Map country name to ISO 3166-1 alpha-2 country code for Nominatim countrycodes parameter.
     * This constrains search results to the specified country for better accuracy.
     */
    private String mapCountryToCode(String country) {
        if (country == null) return null;

        return switch (country.toLowerCase().trim()) {
            case "uk", "united kingdom", "great britain", "britain", "england", "scotland", "wales", "northern ireland" -> "gb";
            case "usa", "united states", "united states of america", "america" -> "us";
            case "ireland", "republic of ireland" -> "ie";
            case "france" -> "fr";
            case "germany", "deutschland" -> "de";
            case "italy", "italia" -> "it";
            case "spain", "españa" -> "es";
            case "portugal" -> "pt";
            case "netherlands", "holland" -> "nl";
            case "belgium" -> "be";
            case "switzerland" -> "ch";
            case "austria", "österreich" -> "at";
            case "denmark", "danmark" -> "dk";
            case "sweden", "sverige" -> "se";
            case "norway", "norge" -> "no";
            case "finland", "suomi" -> "fi";
            case "poland", "polska" -> "pl";
            case "czech republic", "czechia" -> "cz";
            case "australia" -> "au";
            case "new zealand" -> "nz";
            case "canada" -> "ca";
            case "japan" -> "jp";
            case "south korea", "korea" -> "kr";
            case "china" -> "cn";
            case "india" -> "in";
            case "brazil", "brasil" -> "br";
            case "mexico" -> "mx";
            case "colombia" -> "co";
            case "costa rica" -> "cr";
            case "ethiopia" -> "et";
            case "kenya" -> "ke";
            case "rwanda" -> "rw";
            case "tanzania" -> "tz";
            case "uganda" -> "ug";
            case "guatemala" -> "gt";
            case "honduras" -> "hn";
            case "nicaragua" -> "ni";
            case "panama" -> "pa";
            case "peru" -> "pe";
            case "bolivia" -> "bo";
            case "ecuador" -> "ec";
            case "yemen" -> "ye";
            case "indonesia" -> "id";
            case "vietnam" -> "vn";
            case "thailand" -> "th";
            case "myanmar", "burma" -> "mm";
            case "papua new guinea", "png" -> "pg";
            default -> null; // Return null for unknown countries, Nominatim will search globally
        };
    }
}
