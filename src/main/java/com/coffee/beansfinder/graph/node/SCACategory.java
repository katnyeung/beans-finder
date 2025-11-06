package com.coffee.beansfinder.graph.node;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("SCACategory")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SCACategory {

    @Id
    private String name; // e.g., "Fruity", "Floral", "Nutty" - serves as natural key

    private String parentCategory; // For hierarchical structure
}
