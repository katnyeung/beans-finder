package com.coffee.beansfinder.dto;

import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.entity.UserProductTracking;
import com.coffee.beansfinder.entity.UserProductTracking.TrackingStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * DTO for product tracking requests and responses
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class ProductTrackingDTO {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private Long productId;
    private String productName;
    private String brandName;
    private TrackingStatus status;
    private Integer rating;
    private String notes;
    private Boolean includedInChat;
    private LocalDateTime updatedAt;

    // Product details for display
    private List<String> tastingNotes;      // Parsed from tastingNotesJson
    private List<Double> flavorProfile;     // 9-dim SCA vector [fruity, floral, sweet, nutty, spices, roasted, green, sour, other]
    private String origin;
    private String roastLevel;

    // For requests (only need status, rating, notes)
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        private String status;  // "love", "bought", "want", "dislike"
        private Integer rating;  // 1-5, optional
        private String notes;    // optional

        public TrackingStatus getTrackingStatus() {
            if (status == null) return null;
            return TrackingStatus.valueOf(status.toUpperCase());
        }
    }

    public static ProductTrackingDTO fromEntity(UserProductTracking tracking) {
        CoffeeProduct product = tracking.getProduct();

        return ProductTrackingDTO.builder()
                .productId(product.getId())
                .productName(product.getProductName())
                .brandName(product.getBrand() != null
                        ? product.getBrand().getName() : null)
                .status(tracking.getStatus())
                .rating(tracking.getRating() != null ? tracking.getRating().intValue() : null)
                .notes(tracking.getNotes())
                .includedInChat(tracking.getIncludedInChat())
                .updatedAt(tracking.getUpdatedAt())
                // Product details
                .tastingNotes(parseTastingNotes(product.getTastingNotesJson()))
                .flavorProfile(parseFlavorProfile(product.getFlavorProfileJson()))
                .origin(product.getOrigin())
                .roastLevel(product.getRoastLevel())
                .build();
    }

    private static List<String> parseTastingNotes(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.debug("Failed to parse tasting notes: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<Double> parseFlavorProfile(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Double>>() {});
        } catch (Exception e) {
            log.debug("Failed to parse flavor profile: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
