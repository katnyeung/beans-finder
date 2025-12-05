package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Represents Grok LLM's decision on what graph query to execute
 * Parsed from Grok's JSON response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrokDecision {
    /**
     * Type of graph query to execute
     */
    private GraphQueryType queryType;

    /**
     * Filters to apply to the graph query
     */
    private QueryFilters filters;

    /**
     * Natural language response to user
     */
    private String response;

    /**
     * Suggested next actions for the user
     */
    private List<SuggestedAction> suggestedActions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryFilters {
        /**
         * Product name to search for (for SEARCH_BY_NAME queries)
         */
        private String productName;

        /**
         * Brand name to search for (for SEARCH_BY_BRAND queries)
         */
        private String brandName;

        /**
         * SCA category to filter by (e.g., "roasted", "fruity", "sour")
         */
        private String scaCategory;

        /**
         * Roast level to filter by
         */
        private String roastLevel;

        /**
         * Maximum price
         */
        private BigDecimal maxPrice;

        /**
         * Minimum price
         */
        private BigDecimal minPrice;

        /**
         * Origin country to filter by
         */
        private String origin;

        /**
         * Process type to filter by
         */
        private String process;

        /**
         * Character axis for MORE_CHARACTER/LESS_CHARACTER queries.
         * Values: "acidity", "body", "roast", "complexity"
         */
        private String characterAxis;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedAction {
        /**
         * Display label for the button (e.g., "More Bitter", "Same Origin")
         */
        private String label;

        /**
         * Intent identifier (e.g., "more_roasted", "explore_ethiopian")
         */
        private String intent;

        /**
         * Optional icon/emoji for the button
         */
        private String icon;
    }

    /**
     * Types of graph queries Grok can request
     */
    public enum GraphQueryType {
        SEARCH_BY_NAME,            // Search for a specific product by name
        SEARCH_BY_BRAND,           // Search for products by brand name
        SIMILAR_FLAVORS,           // Products with overlapping flavors
        SAME_ORIGIN,               // Products from same origin
        SAME_ROAST,                // Products with same roast level
        SAME_PROCESS,              // Products with same process
        MORE_CATEGORY,             // Products with MORE of a specific SCA category
        LESS_CATEGORY,             // Products with LESS of a specific SCA category
        SAME_ORIGIN_MORE_CATEGORY, // Same origin + more of a category
        SAME_ORIGIN_DIFFERENT_ROAST, // Same origin + different roast level
        // Character axes queries (4-dimensional: acidity, body, roast, complexity)
        MORE_CHARACTER,            // Products with MORE of a character axis (e.g., more acidity, more body)
        LESS_CHARACTER,            // Products with LESS of a character axis (e.g., less acidic, lighter body)
        SIMILAR_PROFILE,           // Products with similar overall profile (cosine similarity)
        CUSTOM                     // Custom combination of filters
    }
}
