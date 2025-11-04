package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.ProducerNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProducerNodeRepository extends Neo4jRepository<ProducerNode, Long> {

    Optional<ProducerNode> findByName(String name);
}
