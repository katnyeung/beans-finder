package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.neo4j.CoffeeNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CoffeeNodeRepository extends Neo4jRepository<CoffeeNode, Long> {

    Optional<CoffeeNode> findByProductId(Long productId);

    @Query("MATCH (p:Product)-[:HAS_FLAVOR]->(f:Flavor) " +
           "WHERE f.specific CONTAINS $flavor " +
           "RETURN p")
    List<CoffeeNode> findByFlavorContaining(@Param("flavor") String flavor);

    @Query("MATCH (p:Product)-[:HAS_FLAVOR]->(f:Flavor)-[:BELONGS_TO_WHEEL]->(s:SCACategory) " +
           "WHERE s.category = $category " +
           "RETURN p")
    List<CoffeeNode> findBySCACategory(@Param("category") String category);

    @Query("MATCH (p:Product)-[:HAS_PROCESS]->(pr:Process) " +
           "WHERE pr.type = $processType " +
           "RETURN p")
    List<CoffeeNode> findByProcess(@Param("processType") String processType);

    @Query("MATCH (p:Product)-[:OF_VARIETY]->(v:Variety) " +
           "WHERE v.name = $variety " +
           "RETURN p")
    List<CoffeeNode> findByVariety(@Param("variety") String variety);

    @Query("MATCH (p:Product)-[:FROM_ORIGIN]->(o:Origin) " +
           "WHERE o.country = $country " +
           "RETURN p")
    List<CoffeeNode> findByOriginCountry(@Param("country") String country);

    // Complex query: Find coffee by process AND flavor category
    @Query("MATCH (p:Product)-[:HAS_PROCESS]->(pr:Process), " +
           "(p)-[:HAS_FLAVOR]->(f:Flavor)-[:BELONGS_TO_WHEEL]->(s:SCACategory) " +
           "WHERE pr.type = $processType AND s.category = $scaCategory " +
           "RETURN p")
    List<CoffeeNode> findByProcessAndSCACategory(
        @Param("processType") String processType,
        @Param("scaCategory") String scaCategory
    );
}
