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

    // ========================================
    // User Preference Fields (for personalized recommendations)
    // ========================================

    /**
     * Average flavor profile vector from user's loved products (9-dimensional)
     * Indices: 0=fruity, 1=floral, 2=sweet, 3=nutty, 4=spices, 5=roasted, 6=green, 7=sour, 8=other
     */
    private List<Double> preferredFlavorVector;

    /**
     * Average flavor profile vector from user's disliked products (9-dimensional)
     * Products similar to this should be avoided
     */
    private List<Double> avoidFlavorVector;

    /**
     * Origins from user's loved products
     */
    private List<String> preferredOrigins;

    /**
     * Origins from user's disliked products (to avoid)
     */
    private List<String> avoidOrigins;

    // Legacy fields for backwards compatibility with Grok prompts
    private List<String> preferredFlavors;
    private List<String> avoidFlavors;

    /**
     * Count of loved products used to build preference vector
     */
    private int lovedProductCount;

    /**
     * Count of disliked products used to build avoid vector
     */
    private int dislikedProductCount;

    /**
     * Format as readable text for Grok prompt
     */
    public String toPromptText(String originName, String roastLevel, String process) {
        StringBuilder sb = new StringBuilder();

        sb.append(String.format("""
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
        ));

        // Add user preferences if available
        if (hasUserPreferences()) {
            sb.append("\n=== USER TASTE PROFILE ===\n");
            // Clarify that this is aggregated from multiple products
            if (lovedProductCount > 0) {
                sb.append(String.format("(Based on %d coffee%s the user loves)\n",
                        lovedProductCount, lovedProductCount > 1 ? "s" : ""));
            }
            List<String> prefCategories = getPreferredCategories();
            if (!prefCategories.isEmpty()) {
                sb.append("User LOVES these flavor categories: ").append(String.join(", ", prefCategories)).append("\n");
            }
            if (preferredOrigins != null && !preferredOrigins.isEmpty()) {
                sb.append("User LOVES these origins: ").append(String.join(", ", preferredOrigins)).append("\n");
            }
            List<String> avoidCategories = getAvoidCategories();
            if (!avoidCategories.isEmpty()) {
                sb.append("User DISLIKES these flavor categories (AVOID): ").append(String.join(", ", avoidCategories)).append("\n");
            }
            if (avoidOrigins != null && !avoidOrigins.isEmpty()) {
                sb.append("User DISLIKES these origins (AVOID): ").append(String.join(", ", avoidOrigins)).append("\n");
            }
            sb.append("\nIMPORTANT: When responding, say 'based on your taste profile' or 'based on the coffees you love' (plural if multiple). ");
            sb.append("Do NOT reference a single specific product name. Prioritize products matching user's preferred categories/origins. ");
            sb.append("Deprioritize products matching user's disliked categories/origins.\n");
        }

        return sb.toString();
    }

    /**
     * Check if user preferences are set
     */
    public boolean hasUserPreferences() {
        return (preferredFlavorVector != null && !preferredFlavorVector.isEmpty()) ||
               (avoidFlavorVector != null && !avoidFlavorVector.isEmpty()) ||
               (preferredOrigins != null && !preferredOrigins.isEmpty()) ||
               (avoidOrigins != null && !avoidOrigins.isEmpty());
    }

    /**
     * Get dominant SCA categories from preferred flavor vector (for Grok prompt)
     */
    public List<String> getPreferredCategories() {
        if (preferredFlavorVector == null || preferredFlavorVector.size() != 9) {
            return List.of();
        }
        String[] categories = {"fruity", "floral", "sweet", "nutty", "spices", "roasted", "green", "sour", "other"};
        java.util.List<String> dominant = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (preferredFlavorVector.get(i) > 0.3) { // Threshold for "significant"
                dominant.add(categories[i]);
            }
        }
        return dominant;
    }

    /**
     * Get dominant SCA categories from avoid flavor vector (for Grok prompt)
     */
    public List<String> getAvoidCategories() {
        if (avoidFlavorVector == null || avoidFlavorVector.size() != 9) {
            return List.of();
        }
        String[] categories = {"fruity", "floral", "sweet", "nutty", "spices", "roasted", "green", "sour", "other"};
        java.util.List<String> dominant = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (avoidFlavorVector.get(i) > 0.3) { // Threshold for "significant"
                dominant.add(categories[i]);
            }
        }
        return dominant;
    }
}
