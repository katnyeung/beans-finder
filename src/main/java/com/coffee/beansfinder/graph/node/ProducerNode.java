package com.coffee.beansfinder.graph.node;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("Producer")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProducerNode {

    @Id
    private String id; // Generated from name-country combination

    private String name;
    private String country;

    // Geolocation fields
    private String address;
    private String city;
    private String region;
    private Double latitude;
    private Double longitude;

    /**
     * Generate natural key from name and country
     */
    public static String generateId(String name, String country) {
        String namePart = (name != null ? name : "Unknown").replaceAll("[^a-zA-Z0-9]", "");
        String countryPart = (country != null ? country : "Unknown").replaceAll("[^a-zA-Z0-9]", "");
        return namePart + "-" + countryPart;
    }
}
