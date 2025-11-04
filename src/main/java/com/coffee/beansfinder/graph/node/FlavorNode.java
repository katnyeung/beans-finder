package com.coffee.beansfinder.graph.node;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
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
    @GeneratedValue
    private Long id;

    private String name; // Specific flavor, e.g., "Nashi pear"
    private String scaCategory; // Primary SCA category, e.g., "Fruity"
    private String scaSubcategory; // Subcategory, e.g., "Stone Fruit"

    @Relationship(type = "BELONGS_TO_CATEGORY", direction = Relationship.Direction.OUTGOING)
    private SCACategory category;
}
