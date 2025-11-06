package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.RoastLevelNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoastLevelNodeRepository extends Neo4jRepository<RoastLevelNode, String> {

    Optional<RoastLevelNode> findByLevel(String level);
}
