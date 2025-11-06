package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.SCACategory;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SCACategoryRepository extends Neo4jRepository<SCACategory, String> {
    // name is the @Id, so use findById() instead of custom finder
}
