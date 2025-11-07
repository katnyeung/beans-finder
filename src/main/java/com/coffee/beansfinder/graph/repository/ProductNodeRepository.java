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

    @Query("MATCH (p:Product)-[:HAS_FLAVOR]->(f:Flavor) " +
           "WHERE f.name CONTAINS $flavorName " +
           "RETURN p")
    List<ProductNode> findByFlavorNameContaining(@Param("flavorName") String flavorName);

    @Query("MATCH (p:Product)-[:HAS_FLAVOR]->(f:Flavor)-[:BELONGS_TO_CATEGORY]->(c:SCACategory) " +
           "WHERE c.name = $categoryName " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
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

    /**
     * Find products by brand name (via SOLD_BY relationship)
     */
    @Query("MATCH (p:Product)-[:SOLD_BY]->(b:Brand) " +
           "WHERE b.name = $brandName " +
           "RETURN p")
    List<ProductNode> findByBrandName(@Param("brandName") String brandName);

    /**
     * Find products by roast level
     */
    @Query("MATCH (p:Product)-[:ROASTED_AT]->(r:RoastLevel) " +
           "WHERE r.level = $level " +
           "RETURN p")
    List<ProductNode> findByRoastLevel(@Param("level") String level);

    /**
     * Find products by exact flavor name (for flavor wheel)
     * Using MATCH with pattern to load relationships
     */
    @Query("MATCH (p:Product)-[:HAS_FLAVOR]->(f:Flavor) " +
           "WHERE f.name = $flavorName " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByFlavorName(@Param("flavorName") String flavorName);

    /**
     * Find products that have ALL specified flavors (AND logic)
     */
    @Query("MATCH (p:Product)-[:HAS_FLAVOR]->(f:Flavor) " +
           "WHERE f.name IN $flavorNames " +
           "WITH p, COUNT(DISTINCT f) as matchCount " +
           "WHERE matchCount = $requiredCount " +
           "RETURN p")
    List<ProductNode> findByAllFlavors(
            @Param("flavorNames") List<String> flavorNames,
            @Param("requiredCount") int requiredCount);

    /**
     * Find products that have ANY of the specified flavors (OR logic)
     */
    @Query("MATCH (p:Product)-[:HAS_FLAVOR]->(f:Flavor) " +
           "WHERE f.name IN $flavorNames " +
           "RETURN DISTINCT p")
    List<ProductNode> findByAnyFlavor(@Param("flavorNames") List<String> flavorNames);
}
