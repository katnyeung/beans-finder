package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.BrandNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BrandNodeRepository extends Neo4jRepository<BrandNode, String> {

    Optional<BrandNode> findByName(String name);

    Optional<BrandNode> findByBrandId(Long brandId);
}
