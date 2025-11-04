package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.SCACategory;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SCACategoryRepository extends Neo4jRepository<SCACategory, Long> {

    Optional<SCACategory> findByName(String name);
}
