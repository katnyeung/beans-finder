package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.OriginNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OriginNodeRepository extends Neo4jRepository<OriginNode, Long> {

    Optional<OriginNode> findByCountryAndRegion(String country, String region);
}
