package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.AttributeNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Attribute nodes (Tier 3 of SCA Flavor Wheel hierarchy).
 */
@Repository
public interface AttributeNodeRepository extends Neo4jRepository<AttributeNode, String> {

    /**
     * Find attribute by ID (e.g., "blackberry", "lemon")
     */
    Optional<AttributeNode> findById(String id);

    /**
     * Find all attributes for a given subcategory
     */
    @Query("MATCH (a:Attribute)-[:BELONGS_TO]->(s:Subcategory {id: $subcategoryId}) " +
           "RETURN a")
    List<AttributeNode> findBySubcategoryId(@Param("subcategoryId") String subcategoryId);

    /**
     * Find all attributes with product counts
     */
    @Query("MATCH (a:Attribute) " +
           "OPTIONAL MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote)-[:MATCHES]->(a) " +
           "WITH a, COUNT(DISTINCT p) as productCount " +
           "RETURN {attributeId: a.id, displayName: a.displayName, productCount: productCount} as data " +
           "ORDER BY productCount DESC")
    List<java.util.Map<String, Object>> findAllWithProductCounts();

    /**
     * Find attributes by category (traversing subcategory -> category)
     */
    @Query("MATCH (a:Attribute)-[:BELONGS_TO]->(s:Subcategory)-[:BELONGS_TO]->(c:SCACategory {name: $categoryName}) " +
           "RETURN a")
    List<AttributeNode> findByCategoryName(@Param("categoryName") String categoryName);
}
