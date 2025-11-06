package com.coffee.beansfinder.graph.node;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * Represents a coffee variety in the knowledge graph (e.g., Geisha, Caturra, SL28).
 * Uses the variety name as the natural key (ID).
 */
@Node("Variety")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VarietyNode {

    /**
     * Natural key: the variety name (e.g., "Geisha", "Caturra", "Bourbon").
     * Cleaned and normalized during creation.
     */
    @Id
    private String name;

    /**
     * Optional description or additional information about the variety.
     */
    private String description;
}
