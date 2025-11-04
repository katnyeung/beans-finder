package com.coffee.beansfinder.neo4j;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("Variety")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VarietyNode {

    @Id
    @GeneratedValue
    private Long id;

    private String name; // e.g., "Geisha", "Bourbon", "Typica"
}
