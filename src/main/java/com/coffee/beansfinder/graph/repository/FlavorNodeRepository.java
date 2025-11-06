package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.FlavorNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FlavorNodeRepository extends Neo4jRepository<FlavorNode, String> {
    // name is the @Id, so use findById() instead of custom finder
}
