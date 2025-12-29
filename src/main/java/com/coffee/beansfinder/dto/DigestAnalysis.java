package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DigestAnalysis {
    private List<DigestQuery> queries;
    private String reasoning;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DigestQuery {
        private DigestQueryType type;
        private Map<String, Object> params;
    }

    public enum DigestQueryType {
        // Origin queries
        ORIGIN_ACTIVITY,        // Products by origin, count changes
        ORIGIN_PRICE_TREND,     // Avg price by origin
        ORIGIN_BY_BRAND,        // Which brands stock which origins

        // Brand queries
        BRAND_NEW_PRODUCTS,     // New products by brand this week
        BRAND_ORIGIN_FOCUS,     // What origins is a brand focusing on

        // Process/Variety queries
        PROCESS_TREND,          // Anaerobic, natural, washed counts
        VARIETY_TREND,          // Geisha, Bourbon, etc. counts

        // General
        NEW_PRODUCTS_SUMMARY,   // Overall new products this week
        PRICE_CHANGES           // Significant price movements
    }
}
