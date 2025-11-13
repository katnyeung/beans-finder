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
     * Price
     */
    private BigDecimal price;

    /**
     * Currency (default: GBP)
     */
    private String currency;

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
