package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Graph context statistics from Neo4j
 * Provides RAG context to Grok about what graph explorations are available
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphContext {
    /**
     * Number of products from the same origin as reference
     */
    private long sameOriginCount;

    /**
     * Number of products with same roast level
     */
    private long sameRoastCount;

    /**
     * Number of products with same process
     */
    private long sameProcessCount;

    /**
     * Number of products with similar flavors (any overlap)
     */
    private long similarFlavorCount;

    /**
     * All available origins in the database
     */
    private List<String> availableOrigins;

    /**
     * All available roast levels
     */
    private List<String> availableRoastLevels;

    /**
     * All available processes
     */
    private List<String> availableProcesses;

    /**
     * All SCA flavor categories
     */
    private List<String> scaCategories;

    /**
     * Format as readable text for Grok prompt
     */
    public String toPromptText(String originName, String roastLevel, String process) {
        return String.format("""
                === AVAILABLE GRAPH EXPLORATION OPTIONS ===
                From the Neo4j knowledge graph, you can find:
                1. Products from same origin: %d products from %s
                2. Products with same roast level: %d %s roast products
                3. Products with same process: %d %s process products
                4. Products with similar flavors: %d products share flavors
                5. Products with MORE of specific SCA categories:
                   - "roasted" (bitter): dark chocolate, tobacco, smoky
                   - "fruity" (berry, citrus, tropical)
                   - "sour" (acidic notes)
                   - "sweet" (honey, caramel, vanilla)
                   - "floral", "nutty", "spices", etc.
                6. Products from different origins: %s
                7. Products with different roast levels: %s
                """,
                sameOriginCount, originName,
                sameRoastCount, roastLevel,
                sameProcessCount, process,
                similarFlavorCount,
                String.join(", ", availableOrigins),
                String.join(", ", availableRoastLevels)
        );
    }
}
