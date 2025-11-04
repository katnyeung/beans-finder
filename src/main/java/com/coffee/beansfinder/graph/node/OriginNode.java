package com.coffee.beansfinder.graph.node;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("Origin")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OriginNode {

    @Id
    @GeneratedValue
    private Long id;

    private String country;
    private String region;
    private String altitude;
}
