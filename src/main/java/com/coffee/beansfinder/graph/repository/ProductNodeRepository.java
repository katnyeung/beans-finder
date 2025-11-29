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

    @Query("MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote) " +
           "WHERE tn.rawText CONTAINS $flavorName " +
           "RETURN p")
    List<ProductNode> findByFlavorNameContaining(@Param("flavorName") String flavorName);

    @Query("MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote)-[:MATCHES]->(a:Attribute)-[:BELONGS_TO]->(s:Subcategory)-[:BELONGS_TO]->(c:SCACategory) " +
           "WHERE c.name = $categoryName " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findBySCACategory(@Param("categoryName") String categoryName);

    @Query("MATCH (p:Product)-[:FROM_ORIGIN]->(o:Origin) " +
           "WHERE o.country = $country " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByOriginCountry(@Param("country") String country);

    @Query("MATCH (p:Product)-[:HAS_PROCESS]->(pr:Process) " +
           "WHERE pr.type = $processType " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByProcessType(@Param("processType") String processType);

    @Query("MATCH (p:Product)-[:HAS_PROCESS]->(pr:Process), " +
           "(p)-[:HAS_TASTING_NOTE]->(tn:TastingNote) " +
           "WHERE pr.type CONTAINS $processType " +
           "AND tn.rawText CONTAINS $flavorName " +
           "RETURN p")
    List<ProductNode> findByProcessAndFlavor(
            @Param("processType") String processType,
            @Param("flavorName") String flavorName);

    /**
     * Find products by brand name (via SOLD_BY relationship)
     */
    @Query("MATCH (p:Product)-[:SOLD_BY]->(b:Brand) " +
           "WHERE b.name = $brandName " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByBrandName(@Param("brandName") String brandName);

    /**
     * Find products by roast level
     */
    @Query("MATCH (p:Product)-[:ROASTED_AT]->(r:RoastLevel) " +
           "WHERE r.level = $level " +
           "WITH DISTINCT p " +
           "MATCH (p)-[rel]-(related) " +
           "RETURN p, collect(rel), collect(related)")
    List<ProductNode> findByRoastLevel(@Param("level") String level);

    /**
     * Find products by exact flavor name (for flavor wheel)
     * Uses TastingNote with normalized ID (lowercase)
     */
    @Query("MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote) " +
           "WHERE tn.id = $flavorName " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByFlavorName(@Param("flavorName") String flavorName);

    /**
     * Find products that have ALL specified flavors (AND logic)
     * Uses TastingNote IDs (normalized lowercase)
     */
    @Query("MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote) " +
           "WHERE tn.id IN $flavorNames " +
           "WITH p, COUNT(DISTINCT tn) as matchCount " +
           "WHERE matchCount = $requiredCount " +
           "RETURN p")
    List<ProductNode> findByAllFlavors(
            @Param("flavorNames") List<String> flavorNames,
            @Param("requiredCount") int requiredCount);

    /**
     * Find products that have ANY of the specified flavors (OR logic)
     * Uses TastingNote IDs (normalized lowercase)
     */
    @Query("MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote) " +
           "WHERE tn.id IN $flavorNames " +
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
     * Uses TastingNote for flavor matching
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:HAS_TASTING_NOTE]->(tn:TastingNote)<-[:HAS_TASTING_NOTE]-(p:Product) " +
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
     * Level 1: Exact TastingNote match
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:HAS_TASTING_NOTE]->(tn:TastingNote)<-[:HAS_TASTING_NOTE]-(p:Product) " +
           "WHERE p.productId <> $refId " +
           "WITH p, COUNT(DISTINCT tn) as flavorOverlap " +
           "ORDER BY flavorOverlap DESC " +
           "LIMIT $limit " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findSimilarByFlavorOverlap(@Param("refId") Long refId, @Param("limit") int limit);

    /**
     * Find products with similar Attribute overlap (ranked by count)
     * Level 2: Match by Attribute (e.g., "lemon" matches other lemon products)
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:HAS_TASTING_NOTE]->(:TastingNote)-[:MATCHES]->(a:Attribute)<-[:MATCHES]-(:TastingNote)<-[:HAS_TASTING_NOTE]-(p:Product) " +
           "WHERE p.productId <> $refId " +
           "WITH p, COUNT(DISTINCT a) as attributeOverlap " +
           "ORDER BY attributeOverlap DESC " +
           "LIMIT $limit " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findSimilarByAttributeOverlap(@Param("refId") Long refId, @Param("limit") int limit);

    /**
     * Find products with similar Subcategory overlap (ranked by count)
     * Level 3: Match by Subcategory (e.g., "citrus_fruit" matches all citrus products)
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:HAS_TASTING_NOTE]->(:TastingNote)-[:MATCHES]->(:Attribute)-[:BELONGS_TO]->(s:Subcategory)<-[:BELONGS_TO]-(:Attribute)<-[:MATCHES]-(:TastingNote)<-[:HAS_TASTING_NOTE]-(p:Product) " +
           "WHERE p.productId <> $refId " +
           "WITH p, COUNT(DISTINCT s) as subcategoryOverlap " +
           "ORDER BY subcategoryOverlap DESC " +
           "LIMIT $limit " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findSimilarBySubcategoryOverlap(@Param("refId") Long refId, @Param("limit") int limit);

    /**
     * Find products with MORE of a specific SCA category
     * Traverses 4-tier hierarchy: TastingNote -> Attribute -> Subcategory -> SCACategory
     */
    @Query("MATCH (ref:Product {productId: $refId}) " +
           "MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote)-[:MATCHES]->(a:Attribute)-[:BELONGS_TO]->(s:Subcategory)-[:BELONGS_TO]->(c:SCACategory {name: $category}) " +
           "WHERE p.productId <> $refId " +
           "WITH p, COUNT(DISTINCT tn) as categoryCount " +
           "ORDER BY categoryCount DESC " +
           "LIMIT $limit " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByMoreCategory(@Param("refId") Long refId, @Param("category") String category, @Param("limit") int limit);

    /**
     * Find products with MORE of a specific SCA category WHILE preserving base flavor overlap
     * Requires at least 1 shared tasting note with reference product
     * This preserves the original flavor profile when user asks for "more X" (e.g., "more bitter" from fruity coffee)
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:HAS_TASTING_NOTE]->(refTn:TastingNote) " +
           "MATCH (p:Product)-[:HAS_TASTING_NOTE]->(refTn) " +
           "WHERE p.productId <> $refId " +
           "WITH p, COUNT(DISTINCT refTn) as baseOverlap " +
           "MATCH (p)-[:HAS_TASTING_NOTE]->(tn:TastingNote)-[:MATCHES]->(a:Attribute)-[:BELONGS_TO]->(s:Subcategory)-[:BELONGS_TO]->(c:SCACategory {name: $category}) " +
           "WITH p, baseOverlap, COUNT(DISTINCT tn) as categoryCount " +
           "ORDER BY categoryCount DESC, baseOverlap DESC " +
           "LIMIT $limit " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByMoreCategoryWithFlavorOverlap(@Param("refId") Long refId, @Param("category") String category, @Param("limit") int limit);

    /**
     * Find products from same origin with MORE of a specific SCA category
     * Traverses 4-tier hierarchy: TastingNote -> Attribute -> Subcategory -> SCACategory
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:FROM_ORIGIN]->(o:Origin)<-[:FROM_ORIGIN]-(p:Product) " +
           "WHERE p.productId <> $refId " +
           "MATCH (p)-[:HAS_TASTING_NOTE]->(tn:TastingNote)-[:MATCHES]->(a:Attribute)-[:BELONGS_TO]->(s:Subcategory)-[:BELONGS_TO]->(c:SCACategory {name: $category}) " +
           "WITH p, COUNT(DISTINCT tn) as categoryCount " +
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
