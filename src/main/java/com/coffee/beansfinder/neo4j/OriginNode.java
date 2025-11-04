package com.coffee.beansfinder.neo4j;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("Origin")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OriginNode {

    @Id
    @GeneratedValue
    private Long id;

    private String country;
    private String region;
}
