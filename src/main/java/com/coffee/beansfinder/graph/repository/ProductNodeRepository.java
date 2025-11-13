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

    /**
     * Find products by name (fuzzy search, case-insensitive)
     * Returns products where productName contains the search term
     */
    @Query("MATCH (p:Product) " +
           "WHERE toLower(p.productName) CONTAINS toLower($productName) " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByProductNameContaining(@Param("productName") String productName);

    // ==================== GRAPH COUNT QUERIES FOR RAG CONTEXT ====================

    /**
     * Count products from same origin as reference product
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:FROM_ORIGIN]->(o:Origin)<-[:FROM_ORIGIN]-(p:Product) " +
           "WHERE p.productId <> $refId " +
           "RETURN COUNT(DISTINCT p)")
    long countBySameOrigin(@Param("refId") Long refId);

    /**
     * Count products with same roast level
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:ROASTED_AT]->(r:RoastLevel)<-[:ROASTED_AT]-(p:Product) " +
           "WHERE p.productId <> $refId " +
           "RETURN COUNT(DISTINCT p)")
    long countBySameRoast(@Param("refId") Long refId);

    /**
     * Count products with same process
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:HAS_PROCESS]->(pr:Process)<-[:HAS_PROCESS]-(p:Product) " +
           "WHERE p.productId <> $refId " +
           "RETURN COUNT(DISTINCT p)")
    long countBySameProcess(@Param("refId") Long refId);

    /**
     * Count products with similar flavors (any overlap)
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:HAS_FLAVOR]->(f:Flavor)<-[:HAS_FLAVOR]-(p:Product) " +
           "WHERE p.productId <> $refId " +
           "RETURN COUNT(DISTINCT p)")
    long countBySimilarFlavors(@Param("refId") Long refId);

    /**
     * Get all available origins in the database
     */
    @Query("MATCH (o:Origin) " +
           "RETURN DISTINCT o.country " +
           "ORDER BY o.country")
    List<String> findAvailableOrigins();

    /**
     * Get all available processes
     */
    @Query("MATCH (pr:Process) " +
           "RETURN DISTINCT pr.type " +
           "ORDER BY pr.type")
    List<String> findAvailableProcesses();

    // ==================== LLM-DRIVEN GRAPH QUERIES ====================

    /**
     * Find products with similar flavor overlap (ranked by count)
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:HAS_FLAVOR]->(f:Flavor)<-[:HAS_FLAVOR]-(p:Product) " +
           "WHERE p.productId <> $refId " +
           "WITH p, COUNT(DISTINCT f) as flavorOverlap " +
           "ORDER BY flavorOverlap DESC " +
           "LIMIT $limit " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findSimilarByFlavorOverlap(@Param("refId") Long refId, @Param("limit") int limit);

    /**
     * Find products with MORE of a specific SCA category
     */
    @Query("MATCH (ref:Product {productId: $refId}) " +
           "MATCH (p:Product)-[:HAS_FLAVOR]->(f:Flavor)-[:BELONGS_TO_CATEGORY]->(c:SCACategory {name: $category}) " +
           "WHERE p.productId <> $refId " +
           "WITH p, COUNT(DISTINCT f) as categoryCount " +
           "ORDER BY categoryCount DESC " +
           "LIMIT $limit " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByMoreCategory(@Param("refId") Long refId, @Param("category") String category, @Param("limit") int limit);

    /**
     * Find products from same origin with MORE of a specific SCA category
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:FROM_ORIGIN]->(o:Origin)<-[:FROM_ORIGIN]-(p:Product) " +
           "WHERE p.productId <> $refId " +
           "MATCH (p)-[:HAS_FLAVOR]->(f:Flavor)-[:BELONGS_TO_CATEGORY]->(c:SCACategory {name: $category}) " +
           "WITH p, COUNT(DISTINCT f) as categoryCount " +
           "ORDER BY categoryCount DESC " +
           "LIMIT $limit " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findBySameOriginMoreCategory(@Param("refId") Long refId, @Param("category") String category, @Param("limit") int limit);

    /**
     * Find products from same origin with different roast level
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:FROM_ORIGIN]->(o:Origin)<-[:FROM_ORIGIN]-(p:Product) " +
           "MATCH (p)-[:ROASTED_AT]->(r:RoastLevel {level: $roastLevel}) " +
           "WHERE p.productId <> $refId " +
           "WITH p " +
           "LIMIT $limit " +
           "MATCH (p)-[rel]-(related) " +
           "RETURN p, collect(rel), collect(related)")
    List<ProductNode> findBySameOriginDifferentRoast(@Param("refId") Long refId, @Param("roastLevel") String roastLevel, @Param("limit") int limit);
}
