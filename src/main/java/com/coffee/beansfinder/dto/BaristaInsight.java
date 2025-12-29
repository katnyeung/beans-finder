package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Barista-style insight for a coffee product.
 * Generated at runtime from origin, process, variety, and character axes.
 * No LLM needed - pure rule-based knowledge.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BaristaInsight {

    /**
     * Pattern name: "Classic washed Ethiopian", "Brazilian natural", etc.
     */
    private String pattern;

    /**
     * Flavor character description: "tea-like clarity, floral and citrus"
     */
    private String character;

    /**
     * What to expect: "Delicate, bright, almost like drinking flavored tea"
     */
    private String expect;

    /**
     * Process effect on flavor: "fruit-forward, heavy body, fermented notes"
     */
    private String processEffect;

    /**
     * Why the process creates this effect: "Dried inside the cherry - fruit sugars infuse the bean"
     */
    private String processWhy;

    /**
     * Variety-specific note if applicable: "The most prized variety - delicate and complex"
     */
    private String varietyNote;

    /**
     * Body feel description: "Light and clean - refreshing, easy drinking"
     */
    private String bodyFeel;

    /**
     * Acidity feel description: "Sparkling, vibrant - the star of the show"
     */
    private String acidityFeel;

    /**
     * Target audience: "Crowd pleaser", "For adventurous palates", etc.
     */
    private String audience;

    /**
     * Rarity note: "Rare find - not often seen in the UK"
     */
    private String rarity;

    /**
     * Whether this is a rare/uncommon origin
     */
    private boolean rare;

    /**
     * Polarization level: CROWD_PLEASER, GENERALLY_LIKED, LOVE_OR_HATE, ADVENTUROUS_ONLY
     */
    private String polarization;

    /**
     * Generate a one-line summary for chat responses
     */
    public String toOneLiner() {
        StringBuilder sb = new StringBuilder();
        if (pattern != null) {
            sb.append(pattern);
        }
        if (character != null) {
            sb.append(" - ").append(character);
        }
        return sb.toString();
    }

    /**
     * Generate a full barista description paragraph
     */
    public String toFullDescription() {
        StringBuilder sb = new StringBuilder();

        // Pattern + character
        if (pattern != null) {
            sb.append(pattern);
            if (character != null) {
                sb.append(" - ").append(character);
            }
            sb.append(". ");
        }

        // Process explanation
        if (processWhy != null) {
            sb.append(processWhy).append(". ");
        }

        // Body feel
        if (bodyFeel != null) {
            sb.append(bodyFeel).append(". ");
        }

        // Audience
        if (audience != null) {
            sb.append(audience).append(" ");
        }

        // Rarity
        if (rarity != null) {
            sb.append(rarity).append(".");
        }

        return sb.toString().trim();
    }
}
