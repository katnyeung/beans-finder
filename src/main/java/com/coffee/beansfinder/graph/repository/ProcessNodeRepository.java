package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.ProcessNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProcessNodeRepository extends Neo4jRepository<ProcessNode, Long> {

    Optional<ProcessNode> findByType(String type);
}
