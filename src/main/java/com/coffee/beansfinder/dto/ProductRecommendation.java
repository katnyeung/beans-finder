package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Product recommendation from chatbot
 * Includes product details + reason for recommendation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductRecommendation {
    /**
     * Product ID (SQL database ID)
     */
    private Long id;

    /**
     * Product name
     */
    private String name;

    /**
     * Brand name
     */
    private String brand;

    /**
     * Price (smallest/default)
     */
    private BigDecimal price;

    /**
     * Price variants (different sizes)
     */
    private List<PriceVariant> priceVariants;

    /**
     * Currency (default: GBP)
     */
    private String currency;

    /**
     * Price variant for different sizes
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceVariant {
        private String size;
        private BigDecimal price;
    }

    /**
     * Flavor notes
     */
    private List<String> flavors;

    /**
     * Origin country
     */
    private String origin;

    /**
     * Roast level
     */
    private String roastLevel;

    /**
     * Product URL
     */
    private String url;

    /**
     * Reason for recommendation (from Grok LLM)
     */
    private String reason;

    /**
     * Similarity score (0.0-1.0)
     * Based on SCA category matching
     */
    private Double similarityScore;
}
