package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.FlavorNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FlavorNodeRepository extends Neo4jRepository<FlavorNode, Long> {

    Optional<FlavorNode> findByName(String name);
}
