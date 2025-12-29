package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Summary of a sitemap crawl operation.
 * Tracks new, updated, unchanged, and deleted products.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlSummary {

    /**
     * Number of new products discovered and added
     */
    private int newProducts;

    /**
     * Number of existing products that had content changes
     */
    private int updatedProducts;

    /**
     * Number of existing products with no content changes (skipped OpenAI)
     */
    private int unchangedProducts;

    /**
     * Number of products deleted (no longer in sitemap)
     */
    private int deletedProducts;

    /**
     * Total URLs processed from sitemap
     */
    private int totalProcessed;

    /**
     * Estimated API cost saved by skipping unchanged products
     * Based on ~$0.0015 per OpenAI extraction
     */
    private double apiCostSaved;

    /**
     * Brand name that was crawled
     */
    private String brandName;

    /**
     * Calculate estimated cost saved
     */
    public static double calculateCostSaved(int unchangedCount) {
        return unchangedCount * 0.0015; // $0.0015 per OpenAI call
    }
}
