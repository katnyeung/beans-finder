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
    private Long productId; // Use application-generated ID from SQL database
    private String productName;
    private String sellerUrl; // Product page URL
    private BigDecimal price;
    private String currency;
    private Boolean inStock;
    private LocalDateTime lastUpdate;

    @Relationship(type = "SOLD_BY", direction = Relationship.Direction.OUTGOING)
    private BrandNode soldBy;

    @Relationship(type = "FROM_ORIGIN", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<OriginNode> origins = new HashSet<>();

    @Relationship(type = "HAS_PROCESS", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<ProcessNode> processes = new HashSet<>();

    /**
     * 4-tier SCA Flavor Wheel hierarchy relationship (Tier 4 - raw tasting notes)
     * Product -> TastingNote -> Attribute -> Subcategory -> SCACategory
     */
    @Relationship(type = "HAS_TASTING_NOTE", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<TastingNoteNode> tastingNotes = new HashSet<>();

    @Relationship(type = "ROASTED_AT", direction = Relationship.Direction.OUTGOING)
    private RoastLevelNode roastLevel;

    @Relationship(type = "PRODUCED_BY", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<ProducerNode> producers = new HashSet<>();

    @Relationship(type = "HAS_VARIETY", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<VarietyNode> varieties = new HashSet<>();
}
