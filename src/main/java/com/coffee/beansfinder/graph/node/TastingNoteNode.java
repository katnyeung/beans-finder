package com.coffee.beansfinder.graph.node;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

/**
 * Tier 4 of SCA Flavor Wheel hierarchy.
 * Represents raw tasting notes from product pages like "blackberry with tea", "lemon meringue pie".
 * Each raw note is linked to its closest SCA Attribute.
 *
 * Hierarchy: SCACategory (9) → Subcategory (35) → Attribute (~110) → TastingNote (many)
 */
@Node("TastingNote")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TastingNoteNode {

    @Id
    private String id; // Normalized lowercase version of rawText (e.g., "blackberry with tea")

    private String rawText; // Original tasting note text (e.g., "Blackberry with Tea")

    @Relationship(type = "MATCHES", direction = Relationship.Direction.OUTGOING)
    private AttributeNode attribute; // Closest SCA attribute (e.g., "blackberry")

    /**
     * Get the SCA category name by traversing the hierarchy.
     * TastingNote -> Attribute -> Subcategory -> SCACategory
     */
    public String getScaCategory() {
        if (attribute == null) return "other";
        if (attribute.getSubcategory() == null) return "other";
        if (attribute.getSubcategory().getCategory() == null) return "other";
        return attribute.getSubcategory().getCategory().getName();
    }

    /**
     * Get display name (alias for rawText for compatibility)
     */
    public String getName() {
        return rawText != null ? rawText : id;
    }
}
