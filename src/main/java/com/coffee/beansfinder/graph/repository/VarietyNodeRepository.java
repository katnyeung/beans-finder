package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.VarietyNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for VarietyNode operations in Neo4j.
 */
@Repository
public interface VarietyNodeRepository extends Neo4jRepository<VarietyNode, String> {

    /**
     * Find variety by name (case-sensitive).
     *
     * @param name the variety name
     * @return Optional containing the variety if found
     */
    Optional<VarietyNode> findByName(String name);
}
