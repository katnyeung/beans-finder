package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.ProducerNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProducerNodeRepository extends Neo4jRepository<ProducerNode, String> {
    // id is generated from name-country, so use findById() instead of custom finder
}
