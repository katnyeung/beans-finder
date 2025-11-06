package com.coffee.beansfinder.graph.node;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("Process")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessNode {

    @Id
    private String type; // e.g., "Honey Anaerobic", "Washed", "Natural" - serves as natural key
}
