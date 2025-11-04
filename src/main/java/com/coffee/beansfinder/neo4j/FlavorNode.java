package com.coffee.beansfinder.neo4j;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

@Node("Flavor")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlavorNode {

    @Id
    @GeneratedValue
    private Long id;

    private String specific; // e.g., "Nashi pear", "oolong"
    private String scaCategory; // e.g., "Fruity", "Floral"
    private String scaSubcategory; // e.g., "Stone Fruit", "Tea-like"

    @Relationship(type = "BELONGS_TO_WHEEL", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<SCAWheelNode> scaCategories = new HashSet<>();
}
