package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.graph.node.TastingNoteNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository for TastingNote nodes (Tier 4 of SCA Flavor Wheel hierarchy).
 */
@Repository
public interface TastingNoteNodeRepository extends Neo4jRepository<TastingNoteNode, String> {

    /**
     * Find tasting note by ID (normalized lowercase text)
     */
    Optional<TastingNoteNode> findById(String id);

    /**
     * Find all tasting notes that match a specific attribute
     */
    @Query("MATCH (tn:TastingNote)-[:MATCHES]->(a:Attribute {id: $attributeId}) " +
           "RETURN tn")
    List<TastingNoteNode> findByAttributeId(@Param("attributeId") String attributeId);

    /**
     * Find tasting notes with their full hierarchy (for debugging)
     */
    @Query("MATCH (tn:TastingNote)-[:MATCHES]->(a:Attribute)-[:BELONGS_TO]->(s:Subcategory)-[:BELONGS_TO]->(c:SCACategory) " +
           "WHERE tn.id = $noteId " +
           "RETURN {rawText: tn.rawText, attribute: a.id, subcategory: s.id, category: c.name} as data")
    Map<String, Object> findWithHierarchy(@Param("noteId") String noteId);

    /**
     * Find products that have a specific tasting note
     */
    @Query("MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote {id: $noteId}) " +
           "RETURN p.productId as productId, p.productName as productName")
    List<Map<String, Object>> findProductsWithNote(@Param("noteId") String noteId);

    /**
     * Find all tasting notes with product counts, grouped by SCA category.
     * Traverses: TastingNote -> Attribute -> Subcategory -> SCACategory
     * Used for flavor wheel visualization.
     */
    @Query("MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote) " +
           "OPTIONAL MATCH (tn)-[:MATCHES]->(a:Attribute)-[:BELONGS_TO]->(s:Subcategory)-[:BELONGS_TO]->(c:SCACategory) " +
           "WITH tn.rawText as flavorName, " +
           "     COALESCE(c.name, 'other') as category, " +
           "     COUNT(DISTINCT p) as productCount " +
           "WHERE productCount > 0 AND flavorName IS NOT NULL " +
           "RETURN {flavorName: flavorName, category: category, productCount: productCount} as data " +
           "ORDER BY category, productCount DESC")
    List<Map<String, Object>> findAllTastingNotesWithProductCountsAsMap();

    /**
     * Find correlated tasting notes (co-occurrence analysis).
     * Returns notes that appear together with the target note.
     */
    @Query("MATCH (tn1:TastingNote {id: $noteId})<-[:HAS_TASTING_NOTE]-(p:Product)-[:HAS_TASTING_NOTE]->(tn2:TastingNote) " +
           "WHERE tn1.id <> tn2.id " +
           "OPTIONAL MATCH (tn2)-[:MATCHES]->(a:Attribute)-[:BELONGS_TO]->(s:Subcategory)-[:BELONGS_TO]->(c:SCACategory) " +
           "WITH tn2.rawText as correlatedFlavor, " +
           "     COALESCE(c.name, 'other') as category, " +
           "     COUNT(DISTINCT p) as coOccurrenceCount " +
           "MATCH (tn1:TastingNote {id: $noteId})<-[:HAS_TASTING_NOTE]-(totalP:Product) " +
           "WITH correlatedFlavor, category, coOccurrenceCount, COUNT(DISTINCT totalP) as totalProducts " +
           "WITH correlatedFlavor, category, coOccurrenceCount, totalProducts, " +
           "     ROUND((coOccurrenceCount * 100.0) / totalProducts) as percentage " +
           "WHERE coOccurrenceCount >= 2 " +
           "RETURN {correlatedFlavor: correlatedFlavor, category: category, coOccurrenceCount: coOccurrenceCount, percentage: percentage} as data " +
           "ORDER BY percentage DESC " +
           "LIMIT 20")
    List<Map<String, Object>> findCorrelatedTastingNotes(@Param("noteId") String noteId);

    /**
     * Get top tasting notes aggregated by country for all coffee origins.
     * Used for map visualization flavor labels.
     */
    @Query("MATCH (p:Product)-[:FROM_ORIGIN]->(o:Origin) " +
           "MATCH (p)-[:HAS_TASTING_NOTE]->(tn:TastingNote) " +
           "WHERE o.country IS NOT NULL " +
           "OPTIONAL MATCH (tn)-[:MATCHES]->(a:Attribute)-[:BELONGS_TO]->(s:Subcategory)-[:BELONGS_TO]->(c:SCACategory) " +
           "WITH o.country as country, tn.rawText as flavorName, COALESCE(c.name, 'other') as category, COUNT(DISTINCT p) as productCount " +
           "WITH country, collect({flavor: flavorName, category: category, productCount: productCount}) as allFlavors, SUM(productCount) as totalProducts " +
           "UNWIND allFlavors as flavorData " +
           "WITH country, flavorData.flavor as flavor, flavorData.category as category, flavorData.productCount as productCount, totalProducts " +
           "WITH country, flavor, category, productCount, totalProducts, " +
           "     ROUND((productCount * 100.0) / totalProducts) as percentage " +
           "ORDER BY country, productCount DESC " +
           "WITH country, collect({flavor: flavor, category: category, productCount: productCount, percentage: percentage})[0..5] as topFlavors " +
           "RETURN {country: country, topFlavors: topFlavors} as data")
    List<Map<String, Object>> findTopTastingNotesByCountry();
}
