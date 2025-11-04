package com.coffee.beansfinder.graph.node;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Node("Product")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductNode {

    @Id
    @GeneratedValue
    private Long id;

    private Long productId; // Reference to SQL database ID
    private String brand;
    private String productName;
    private String variety;
    private BigDecimal price;
    private String currency;
    private Boolean inStock;
    private LocalDateTime lastUpdate;

    @Relationship(type = "FROM_ORIGIN", direction = Relationship.Direction.OUTGOING)
    private OriginNode origin;

    @Relationship(type = "HAS_PROCESS", direction = Relationship.Direction.OUTGOING)
    private ProcessNode process;

    @Relationship(type = "HAS_FLAVOR", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<FlavorNode> flavors = new HashSet<>();

    @Relationship(type = "PRODUCED_BY", direction = Relationship.Direction.OUTGOING)
    private ProducerNode producer;
}
