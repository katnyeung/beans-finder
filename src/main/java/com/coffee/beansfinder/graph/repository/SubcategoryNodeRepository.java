package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.SubcategoryNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Subcategory nodes (Tier 2 of SCA Flavor Wheel hierarchy).
 */
@Repository
public interface SubcategoryNodeRepository extends Neo4jRepository<SubcategoryNode, String> {

    /**
     * Find subcategory by ID (e.g., "berry", "citrus_fruit")
     */
    Optional<SubcategoryNode> findById(String id);

    /**
     * Find all subcategories for a given category
     */
    @Query("MATCH (s:Subcategory)-[:BELONGS_TO]->(c:SCACategory {name: $categoryName}) " +
           "RETURN s")
    List<SubcategoryNode> findByCategoryName(String categoryName);

    /**
     * Get all subcategories with their parent category
     */
    @Query("MATCH (s:Subcategory)-[:BELONGS_TO]->(c:SCACategory) " +
           "RETURN s, c")
    List<SubcategoryNode> findAllWithCategory();
}
