package com.coffee.beansfinder.graph.node;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

@Node("Flavor")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlavorNode {

    @Id
    private String name; // Specific flavor, e.g., "Nashi pear" - serves as natural key
    private String scaCategory; // Primary SCA category, e.g., "Fruity"
    private String scaSubcategory; // Subcategory, e.g., "Stone Fruit"

    @Relationship(type = "BELONGS_TO_CATEGORY", direction = Relationship.Direction.OUTGOING)
    private SCACategory category;
}
