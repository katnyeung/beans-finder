package com.coffee.beansfinder.graph.node;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

/**
 * Tier 3 of SCA Flavor Wheel hierarchy.
 * Represents specific flavor attributes like "blackberry", "lemon", "caramel", etc.
 * These are the ~110 attributes from the WCR Sensory Lexicon.
 *
 * Hierarchy: SCACategory (9) → Subcategory (35) → Attribute (~110) → TastingNote (many)
 */
@Node("Attribute")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttributeNode {

    @Id
    private String id; // e.g., "blackberry", "lemon", "caramel" (lowercase, normalized)

    private String displayName; // e.g., "Blackberry", "Lemon", "Caramel"

    @Relationship(type = "BELONGS_TO", direction = Relationship.Direction.OUTGOING)
    private SubcategoryNode subcategory; // Parent subcategory (e.g., "berry")
}
