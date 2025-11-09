package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.ProducerNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProducerNodeRepository extends Neo4jRepository<ProducerNode, String> {

    @Query("MATCH (p:Producer) WHERE toLower(p.name) CONTAINS toLower($name) RETURN p")
    List<ProducerNode> findByNameContainingIgnoreCase(@Param("name") String name);
}
