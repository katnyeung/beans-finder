package com.coffee.beansfinder.neo4j;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

@Node("Product")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CoffeeNode {

    @Id
    @GeneratedValue
    private Long id;

    private Long productId; // Reference to PostgreSQL ID
    private String name;
    private String brand;
    private BigDecimal price;
    private String currency;

    @Relationship(type = "FROM_ORIGIN", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<OriginNode> origins = new HashSet<>();

    @Relationship(type = "HAS_PROCESS", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<ProcessNode> processes = new HashSet<>();

    @Relationship(type = "HAS_FLAVOR", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<FlavorNode> flavors = new HashSet<>();

    @Relationship(type = "OF_VARIETY", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<VarietyNode> varieties = new HashSet<>();
}
