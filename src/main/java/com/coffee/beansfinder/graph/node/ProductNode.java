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
import java.util.List;
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

    /**
     * 9-dimensional flavor profile [0.0-1.0] for SCA category intensities.
     * Indices: 0=fruity, 1=floral, 2=sweet, 3=nutty, 4=spices, 5=roasted, 6=green, 7=sour, 8=other
     */
    private List<Double> flavorProfile;

    /**
     * 4-dimensional character axes [-1.0 to +1.0] for coffee character spectrum.
     * Indices: 0=acidity (flat↔bright), 1=body (light↔full), 2=roast (light↔dark), 3=complexity (clean↔funky)
     */
    private List<Double> characterAxes;
}
