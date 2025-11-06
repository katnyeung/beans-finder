package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.ProcessNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessNodeRepository extends Neo4jRepository<ProcessNode, String> {
    // type is the @Id, so use findById() instead of custom finder
}
