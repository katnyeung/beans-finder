package com.coffee.beansfinder.graph.node;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

/**
 * Tier 2 of SCA Flavor Wheel hierarchy.
 * Represents subcategories like "berry", "citrus_fruit", "cocoa", etc.
 *
 * Hierarchy: SCACategory (9) → Subcategory (35) → Attribute (~110) → TastingNote (many)
 */
@Node("Subcategory")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubcategoryNode {

    @Id
    private String id; // e.g., "berry", "citrus_fruit", "cocoa"

    private String displayName; // e.g., "Berry", "Citrus Fruit", "Cocoa"

    private String description; // e.g., "Sweet, sour, floral aromatics of berries"

    @Relationship(type = "BELONGS_TO", direction = Relationship.Direction.OUTGOING)
    private SCACategory category; // Parent category (e.g., "fruity")
}
