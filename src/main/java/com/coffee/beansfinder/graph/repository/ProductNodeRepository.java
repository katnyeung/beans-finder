package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.ProductNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductNodeRepository extends Neo4jRepository<ProductNode, Long> {

    Optional<ProductNode> findByProductId(Long productId);

    List<ProductNode> findByBrand(String brand);

    @Query("MATCH (p:Product)-[:HAS_FLAVOR]->(f:Flavor) " +
           "WHERE f.name CONTAINS $flavorName " +
           "RETURN p")
    List<ProductNode> findByFlavorNameContaining(@Param("flavorName") String flavorName);

    @Query("MATCH (p:Product)-[:HAS_FLAVOR]->(f:Flavor)-[:BELONGS_TO_CATEGORY]->(c:SCACategory) " +
           "WHERE c.name = $categoryName " +
           "RETURN p")
    List<ProductNode> findBySCACategory(@Param("categoryName") String categoryName);

    @Query("MATCH (p:Product)-[:FROM_ORIGIN]->(o:Origin) " +
           "WHERE o.country = $country " +
           "RETURN p")
    List<ProductNode> findByOriginCountry(@Param("country") String country);

    @Query("MATCH (p:Product)-[:HAS_PROCESS]->(pr:Process) " +
           "WHERE pr.type = $processType " +
           "RETURN p")
    List<ProductNode> findByProcessType(@Param("processType") String processType);

    @Query("MATCH (p:Product)-[:HAS_PROCESS]->(pr:Process), " +
           "(p)-[:HAS_FLAVOR]->(f:Flavor) " +
           "WHERE pr.type CONTAINS $processType " +
           "AND f.name CONTAINS $flavorName " +
           "RETURN p")
    List<ProductNode> findByProcessAndFlavor(
            @Param("processType") String processType,
            @Param("flavorName") String flavorName);
}
