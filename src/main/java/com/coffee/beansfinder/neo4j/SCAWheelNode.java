package com.coffee.beansfinder.neo4j;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("SCACategory")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SCAWheelNode {

    @Id
    @GeneratedValue
    private Long id;

    private String category; // e.g., "Fruity", "Floral", "Nutty"
    private String subcategory; // e.g., "Stone Fruit", "Berry"
}
