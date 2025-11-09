package com.coffee.beansfinder.graph.node;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("Origin")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OriginNode {

    @Id
    private String id; // Generated from country-region combination

    private String country;
    private String region;
    private String altitude;

    // Geolocation fields
    private Double latitude;
    private Double longitude;
    private String boundingBox; // JSON string for region boundaries

    /**
     * Generate natural key from country and region.
     * If region is null/empty, returns just the country (no "-Unknown" suffix).
     */
    public static String generateId(String country, String region) {
        String countryPart = (country != null ? country : "").replaceAll("[^a-zA-Z0-9]", "");

        // Only append region if it's actually present
        if (region != null && !region.trim().isEmpty()) {
            String regionPart = region.replaceAll("[^a-zA-Z0-9]", "");
            return countryPart + "-" + regionPart;
        }

        return countryPart;
    }
}
