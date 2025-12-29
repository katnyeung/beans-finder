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

    /**
     * Delete all outgoing relationships from a product node.
     * Used before re-syncing to ensure old relationships are cleaned up.
     * This prevents stale tasting notes, flavors, origins, etc. from persisting after re-crawl.
     */
    @Query("MATCH (p:Product {productId: $productId})-[r:HAS_TASTING_NOTE|FROM_ORIGIN|HAS_PROCESS|HAS_VARIETY|PRODUCED_BY|ROASTED_AT]->() DELETE r")
    void deleteAllProductRelationships(@Param("productId") Long productId);

    // ==================== SOFT DELETE FILTER ====================
    // Most queries below need to filter: (p.deleted IS NULL OR p.deleted = false)
    // This ensures soft-deleted products are excluded from searches

    /**
     * Find active (non-deleted) product by ID
     */
    @Query("MATCH (p:Product {productId: $productId}) " +
           "WHERE p.deleted IS NULL OR p.deleted = false " +
           "RETURN p")
    Optional<ProductNode> findActiveByProductId(@Param("productId") Long productId);

    @Query("MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote) " +
           "WHERE tn.rawText CONTAINS $flavorName " +
           "AND (p.deleted IS NULL OR p.deleted = false) " +
           "RETURN p")
    List<ProductNode> findByFlavorNameContaining(@Param("flavorName") String flavorName);

    @Query("MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote)-[:MATCHES]->(a:Attribute)-[:BELONGS_TO]->(s:Subcategory)-[:BELONGS_TO]->(c:SCACategory) " +
           "WHERE c.name = $categoryName AND (p.deleted IS NULL OR p.deleted = false) " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findBySCACategory(@Param("categoryName") String categoryName);

    @Query("MATCH (p:Product)-[:FROM_ORIGIN]->(o:Origin) " +
           "WHERE o.country = $country AND (p.deleted IS NULL OR p.deleted = false) " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByOriginCountry(@Param("country") String country);

    @Query("MATCH (p:Product)-[:HAS_PROCESS]->(pr:Process) " +
           "WHERE pr.type = $processType AND (p.deleted IS NULL OR p.deleted = false) " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByProcessType(@Param("processType") String processType);

    @Query("MATCH (p:Product)-[:HAS_PROCESS]->(pr:Process), " +
           "(p)-[:HAS_TASTING_NOTE]->(tn:TastingNote) " +
           "WHERE pr.type CONTAINS $processType " +
           "AND tn.rawText CONTAINS $flavorName " +
           "AND (p.deleted IS NULL OR p.deleted = false) " +
           "RETURN p")
    List<ProductNode> findByProcessAndFlavor(
            @Param("processType") String processType,
            @Param("flavorName") String flavorName);

    /**
     * Find products by brand name (via SOLD_BY relationship, excludes deleted)
     */
    @Query("MATCH (p:Product)-[:SOLD_BY]->(b:Brand) " +
           "WHERE b.name = $brandName AND (p.deleted IS NULL OR p.deleted = false) " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByBrandName(@Param("brandName") String brandName);

    /**
     * Find products by roast level (excludes deleted)
     */
    @Query("MATCH (p:Product)-[:ROASTED_AT]->(r:RoastLevel) " +
           "WHERE r.level = $level AND (p.deleted IS NULL OR p.deleted = false) " +
           "WITH DISTINCT p " +
           "MATCH (p)-[rel]-(related) " +
           "RETURN p, collect(rel), collect(related)")
    List<ProductNode> findByRoastLevel(@Param("level") String level);

    /**
     * Find products by exact flavor name (for flavor wheel, excludes deleted)
     * Uses TastingNote with normalized ID (lowercase)
     */
    @Query("MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote) " +
           "WHERE tn.id = $flavorName AND (p.deleted IS NULL OR p.deleted = false) " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByFlavorName(@Param("flavorName") String flavorName);

    /**
     * Find products that have ALL specified flavors (AND logic, excludes deleted)
     * Uses TastingNote IDs (normalized lowercase)
     * Fetches related nodes (brand, origin, roast) for complete product data
     */
    @Query("MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote) " +
           "WHERE tn.id IN $flavorNames AND (p.deleted IS NULL OR p.deleted = false) " +
           "WITH p, COUNT(DISTINCT tn) as matchCount " +
           "WHERE matchCount = $requiredCount " +
           "WITH p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByAllFlavors(
            @Param("flavorNames") List<String> flavorNames,
            @Param("requiredCount") int requiredCount);

    /**
     * Find products that have ANY of the specified flavors (OR logic, excludes deleted)
     * Uses TastingNote IDs (normalized lowercase)
     * Fetches related nodes (brand, origin, roast) for complete product data
     */
    @Query("MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote) " +
           "WHERE tn.id IN $flavorNames AND (p.deleted IS NULL OR p.deleted = false) " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByAnyFlavor(@Param("flavorNames") List<String> flavorNames);

    /**
     * Find products by Attribute ID (aggregates all TastingNotes that MATCH the attribute).
     * Used for flavor wheel click - clicking "Cherry" returns all products with
     * "cherry", "maraschino cherry", "cherry blossom", etc.
     */
    @Query("MATCH (p:Product)-[:HAS_TASTING_NOTE]->(tn:TastingNote)-[:MATCHES]->(a:Attribute {id: $attributeId}) " +
           "WHERE p.deleted IS NULL OR p.deleted = false " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByAttributeId(@Param("attributeId") String attributeId);

    /**
     * Find products by name (fuzzy search, case-insensitive, excludes deleted)
     * Returns products where productName contains the search term
     */
    @Query("MATCH (p:Product) " +
           "WHERE toLower(p.productName) CONTAINS toLower($productName) " +
           "AND (p.deleted IS NULL OR p.deleted = false) " +
           "WITH DISTINCT p " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByProductNameContaining(@Param("productName") String productName);

    // ==================== GRAPH COUNT QUERIES FOR RAG CONTEXT ====================
    // NOTE: All count queries filter out deleted products

    /**
     * Count products from same origin as reference product (excludes deleted)
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:FROM_ORIGIN]->(o:Origin)<-[:FROM_ORIGIN]-(p:Product) " +
           "WHERE p.productId <> $refId AND (p.deleted IS NULL OR p.deleted = false) " +
           "RETURN COUNT(DISTINCT p)")
    long countBySameOrigin(@Param("refId") Long refId);

    /**
     * Count products with same roast level (excludes deleted)
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:ROASTED_AT]->(r:RoastLevel)<-[:ROASTED_AT]-(p:Product) " +
           "WHERE p.productId <> $refId AND (p.deleted IS NULL OR p.deleted = false) " +
           "RETURN COUNT(DISTINCT p)")
    long countBySameRoast(@Param("refId") Long refId);

    /**
     * Count products with same process (excludes deleted)
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:HAS_PROCESS]->(pr:Process)<-[:HAS_PROCESS]-(p:Product) " +
           "WHERE p.productId <> $refId AND (p.deleted IS NULL OR p.deleted = false) " +
           "RETURN COUNT(DISTINCT p)")
    long countBySameProcess(@Param("refId") Long refId);

    /**
     * Count products with similar flavors (any overlap, excludes deleted)
     * Uses TastingNote for flavor matching
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:HAS_TASTING_NOTE]->(tn:TastingNote)<-[:HAS_TASTING_NOTE]-(p:Product) " +
           "WHERE p.productId <> $refId AND (p.deleted IS NULL OR p.deleted = false) " +
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
    // NOTE: All queries filter out deleted products: (p.deleted IS NULL OR p.deleted = false)

    /**
     * Find products with similar flavor overlap (ranked by count)
     * Level 1: Exact TastingNote match
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:HAS_TASTING_NOTE]->(tn:TastingNote)<-[:HAS_TASTING_NOTE]-(p:Product) " +
           "WHERE p.productId <> $refId AND (p.deleted IS NULL OR p.deleted = false) " +
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
           "WHERE p.productId <> $refId AND (p.deleted IS NULL OR p.deleted = false) " +
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
           "WHERE p.productId <> $refId AND (p.deleted IS NULL OR p.deleted = false) " +
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
           "WHERE p.productId <> $refId AND (p.deleted IS NULL OR p.deleted = false) " +
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
           "WHERE p.productId <> $refId AND (p.deleted IS NULL OR p.deleted = false) " +
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
           "WHERE p.productId <> $refId AND (p.deleted IS NULL OR p.deleted = false) " +
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
           "WHERE p.productId <> $refId AND (p.deleted IS NULL OR p.deleted = false) " +
           "WITH p " +
           "LIMIT $limit " +
           "MATCH (p)-[rel]-(related) " +
           "RETURN p, collect(rel), collect(related)")
    List<ProductNode> findBySameOriginDifferentRoast(@Param("refId") Long refId, @Param("roastLevel") String roastLevel, @Param("limit") int limit);

    // ==================== PROFILE-BASED VECTOR QUERIES ====================
    // NOTE: All queries filter out deleted products: (p.deleted IS NULL OR p.deleted = false)

    /**
     * Find products with MORE of a specific SCA category using flavor profile vector comparison.
     * Uses flavorProfile array: [fruity, floral, sweet, nutty, spices, roasted, green, sour, other]
     * Index mapping: 0=fruity, 1=floral, 2=sweet, 3=nutty, 4=spices, 5=roasted, 6=green, 7=sour, 8=other
     *
     * Balanced scoring: 70% profile similarity + 30% intensity increase
     * This ensures products are similar overall but with MORE of the requested category.
     *
     * NOTE: Loads full 4-tier flavor hierarchy for TastingNote.getScaCategory() to work.
     *
     * @param refId Reference product ID
     * @param categoryIndex Index in flavor profile array (0-8)
     * @param limit Maximum results
     */
    @Query("MATCH (ref:Product {productId: $refId}) " +
           "WHERE ref.flavorProfile IS NOT NULL " +
           "MATCH (p:Product) " +
           "WHERE p.productId <> $refId " +
           "AND (p.deleted IS NULL OR p.deleted = false) " +
           "AND p.flavorProfile IS NOT NULL " +
           "AND size(p.flavorProfile) = 9 " +
           "AND size(ref.flavorProfile) = 9 " +
           "AND p.flavorProfile[$categoryIndex] > ref.flavorProfile[$categoryIndex] " +
           // Calculate cosine similarity for overall profile match
           "WITH p, ref, " +
           "  reduce(dot = 0.0, i IN range(0, 8) | dot + p.flavorProfile[i] * ref.flavorProfile[i]) as dotProduct, " +
           "  sqrt(reduce(s = 0.0, i IN range(0, 8) | s + p.flavorProfile[i] * p.flavorProfile[i])) as normP, " +
           "  sqrt(reduce(s = 0.0, i IN range(0, 8) | s + ref.flavorProfile[i] * ref.flavorProfile[i])) as normRef, " +
           "  (p.flavorProfile[$categoryIndex] - ref.flavorProfile[$categoryIndex]) as intensityDiff " +
           "WITH p, " +
           "  CASE WHEN normP * normRef = 0 THEN 0 ELSE dotProduct / (normP * normRef) END as similarity, " +
           "  intensityDiff " +
           // Balanced score: 70% similarity + 30% intensity increase (normalized)
           "WITH p, similarity, intensityDiff, " +
           "  (0.7 * similarity) + (0.3 * (intensityDiff / 10.0)) as balancedScore " +
           "ORDER BY balancedScore DESC " +
           "LIMIT $limit " +
           // Load direct relationships only (TastingNote rawText is sufficient for display)
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByMoreCategoryProfile(
            @Param("refId") Long refId,
            @Param("categoryIndex") int categoryIndex,
            @Param("limit") int limit);

    /**
     * Find products with LESS of a specific character axis AND shared tasting notes.
     * HYBRID APPROACH: Requires flavor overlap first, then filters by lower axis value.
     *
     * This ensures recommendations maintain flavor similarity while reducing the specified axis.
     * Example: "less acidic" finds coffees with similar fruity notes but lower acidity.
     *
     * @param refId Reference product ID
     * @param axisIndex Index in character axes array (0-3): acidity, body, roast, complexity
     * @param limit Maximum results
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:HAS_TASTING_NOTE]->(tn:TastingNote)<-[:HAS_TASTING_NOTE]-(p:Product) " +
           "WHERE p.productId <> $refId AND (p.deleted IS NULL OR p.deleted = false) " +
           "AND ref.characterAxes IS NOT NULL " +
           "AND p.characterAxes IS NOT NULL " +
           "AND size(ref.characterAxes) = 4 " +
           "AND size(p.characterAxes) = 4 " +
           "AND p.characterAxes[$axisIndex] < ref.characterAxes[$axisIndex] " +
           "WITH p, ref, COUNT(DISTINCT tn) as flavorOverlap, " +
           "  (ref.characterAxes[$axisIndex] - p.characterAxes[$axisIndex]) as axisDiff " +
           // Score: prioritize flavor overlap, then axis difference
           "WITH p, flavorOverlap, axisDiff, " +
           "  (flavorOverlap * 10.0) + (axisDiff * 5.0) as score " +
           "ORDER BY score DESC " +
           "LIMIT $limit " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByLessCharacterAxisWithFlavorOverlap(
            @Param("refId") Long refId,
            @Param("axisIndex") int axisIndex,
            @Param("limit") int limit);

    /**
     * Find products with LESS of a specific character axis using vector comparison only.
     * FALLBACK: Used when no flavor overlap matches are found.
     *
     * Balanced scoring: 70% profile similarity + 30% axis decrease
     *
     * @param refId Reference product ID
     * @param axisIndex Index in character axes array (0-3)
     * @param limit Maximum results
     */
    @Query("MATCH (ref:Product {productId: $refId}) " +
           "WHERE ref.characterAxes IS NOT NULL AND ref.flavorProfile IS NOT NULL " +
           "MATCH (p:Product) " +
           "WHERE p.productId <> $refId " +
           "AND (p.deleted IS NULL OR p.deleted = false) " +
           "AND p.characterAxes IS NOT NULL " +
           "AND p.flavorProfile IS NOT NULL " +
           "AND size(p.characterAxes) = 4 " +
           "AND size(ref.characterAxes) = 4 " +
           "AND size(p.flavorProfile) = 9 " +
           "AND size(ref.flavorProfile) = 9 " +
           "AND p.characterAxes[$axisIndex] < ref.characterAxes[$axisIndex] " +
           // Calculate cosine similarity for overall flavor profile match
           "WITH p, ref, " +
           "  reduce(dot = 0.0, i IN range(0, 8) | dot + p.flavorProfile[i] * ref.flavorProfile[i]) as dotProduct, " +
           "  sqrt(reduce(s = 0.0, i IN range(0, 8) | s + p.flavorProfile[i] * p.flavorProfile[i])) as normP, " +
           "  sqrt(reduce(s = 0.0, i IN range(0, 8) | s + ref.flavorProfile[i] * ref.flavorProfile[i])) as normRef, " +
           "  (ref.characterAxes[$axisIndex] - p.characterAxes[$axisIndex]) as axisDiff " +
           "WITH p, " +
           "  CASE WHEN normP * normRef = 0 THEN 0 ELSE dotProduct / (normP * normRef) END as similarity, " +
           "  axisDiff " +
           // Balanced score: 70% similarity + 30% axis decrease (normalized to 0-1 range)
           "WITH p, similarity, axisDiff, " +
           "  (0.7 * similarity) + (0.3 * (axisDiff / 2.0)) as balancedScore " +
           "ORDER BY balancedScore DESC " +
           "LIMIT $limit " +
           // Load direct relationships only (TastingNote rawText is sufficient for display)
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByLessCharacterAxis(
            @Param("refId") Long refId,
            @Param("axisIndex") int axisIndex,
            @Param("limit") int limit);

    /**
     * Find products with MORE of a specific character axis AND shared tasting notes.
     * HYBRID APPROACH: Requires flavor overlap first, then filters by higher axis value.
     *
     * @param refId Reference product ID
     * @param axisIndex Index in character axes array (0-3): acidity, body, roast, complexity
     * @param limit Maximum results
     */
    @Query("MATCH (ref:Product {productId: $refId})-[:HAS_TASTING_NOTE]->(tn:TastingNote)<-[:HAS_TASTING_NOTE]-(p:Product) " +
           "WHERE p.productId <> $refId AND (p.deleted IS NULL OR p.deleted = false) " +
           "AND ref.characterAxes IS NOT NULL " +
           "AND p.characterAxes IS NOT NULL " +
           "AND size(ref.characterAxes) = 4 " +
           "AND size(p.characterAxes) = 4 " +
           "AND p.characterAxes[$axisIndex] > ref.characterAxes[$axisIndex] " +
           "WITH p, ref, COUNT(DISTINCT tn) as flavorOverlap, " +
           "  (p.characterAxes[$axisIndex] - ref.characterAxes[$axisIndex]) as axisDiff " +
           // Score: prioritize flavor overlap, then axis difference
           "WITH p, flavorOverlap, axisDiff, " +
           "  (flavorOverlap * 10.0) + (axisDiff * 5.0) as score " +
           "ORDER BY score DESC " +
           "LIMIT $limit " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByMoreCharacterAxisWithFlavorOverlap(
            @Param("refId") Long refId,
            @Param("axisIndex") int axisIndex,
            @Param("limit") int limit);

    /**
     * Find products with MORE of a specific character axis using vector comparison only.
     * FALLBACK: Used when no flavor overlap matches are found.
     *
     * Balanced scoring: 70% profile similarity + 30% axis increase
     *
     * @param refId Reference product ID
     * @param axisIndex Index in character axes array (0-3)
     * @param limit Maximum results
     */
    @Query("MATCH (ref:Product {productId: $refId}) " +
           "WHERE ref.characterAxes IS NOT NULL AND ref.flavorProfile IS NOT NULL " +
           "MATCH (p:Product) " +
           "WHERE p.productId <> $refId " +
           "AND (p.deleted IS NULL OR p.deleted = false) " +
           "AND p.characterAxes IS NOT NULL " +
           "AND p.flavorProfile IS NOT NULL " +
           "AND size(p.characterAxes) = 4 " +
           "AND size(ref.characterAxes) = 4 " +
           "AND size(p.flavorProfile) = 9 " +
           "AND size(ref.flavorProfile) = 9 " +
           "AND p.characterAxes[$axisIndex] > ref.characterAxes[$axisIndex] " +
           // Calculate cosine similarity for overall flavor profile match
           "WITH p, ref, " +
           "  reduce(dot = 0.0, i IN range(0, 8) | dot + p.flavorProfile[i] * ref.flavorProfile[i]) as dotProduct, " +
           "  sqrt(reduce(s = 0.0, i IN range(0, 8) | s + p.flavorProfile[i] * p.flavorProfile[i])) as normP, " +
           "  sqrt(reduce(s = 0.0, i IN range(0, 8) | s + ref.flavorProfile[i] * ref.flavorProfile[i])) as normRef, " +
           "  (p.characterAxes[$axisIndex] - ref.characterAxes[$axisIndex]) as axisDiff " +
           "WITH p, " +
           "  CASE WHEN normP * normRef = 0 THEN 0 ELSE dotProduct / (normP * normRef) END as similarity, " +
           "  axisDiff " +
           // Balanced score: 70% similarity + 30% axis increase (normalized to 0-1 range)
           "WITH p, similarity, axisDiff, " +
           "  (0.7 * similarity) + (0.3 * (axisDiff / 2.0)) as balancedScore " +
           "ORDER BY balancedScore DESC " +
           "LIMIT $limit " +
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findByMoreCharacterAxis(
            @Param("refId") Long refId,
            @Param("axisIndex") int axisIndex,
            @Param("limit") int limit);

    /**
     * Find products with similar overall profile using cosine similarity across all 13 dimensions.
     * Combines flavorProfile (9 dims) + characterAxes (4 dims).
     *
     * NOTE: Loads full 4-tier flavor hierarchy for TastingNote.getScaCategory() to work.
     */
    @Query("MATCH (ref:Product {productId: $refId}) " +
           "WHERE ref.flavorProfile IS NOT NULL AND ref.characterAxes IS NOT NULL " +
           "AND size(ref.flavorProfile) = 9 AND size(ref.characterAxes) = 4 " +
           "MATCH (p:Product) " +
           "WHERE p.productId <> $refId " +
           "AND (p.deleted IS NULL OR p.deleted = false) " +
           "AND p.flavorProfile IS NOT NULL AND p.characterAxes IS NOT NULL " +
           "AND size(p.flavorProfile) = 9 AND size(p.characterAxes) = 4 " +
           "WITH p, ref, " +
           "  reduce(dot = 0.0, i IN range(0, 8) | dot + p.flavorProfile[i] * ref.flavorProfile[i]) + " +
           "  reduce(dot = 0.0, i IN range(0, 3) | dot + p.characterAxes[i] * ref.characterAxes[i]) as dotProduct, " +
           "  sqrt(reduce(a = 0.0, i IN range(0, 8) | a + p.flavorProfile[i] * p.flavorProfile[i]) + " +
           "       reduce(a = 0.0, i IN range(0, 3) | a + p.characterAxes[i] * p.characterAxes[i])) as normP, " +
           "  sqrt(reduce(b = 0.0, i IN range(0, 8) | b + ref.flavorProfile[i] * ref.flavorProfile[i]) + " +
           "       reduce(b = 0.0, i IN range(0, 3) | b + ref.characterAxes[i] * ref.characterAxes[i])) as normRef " +
           "WITH p, CASE WHEN normP * normRef = 0 THEN 0 ELSE dotProduct / (normP * normRef) END as similarity " +
           "ORDER BY similarity DESC " +
           "LIMIT $limit " +
           // Load direct relationships only (TastingNote rawText is sufficient for display)
           "MATCH (p)-[r]-(related) " +
           "RETURN p, collect(r), collect(related)")
    List<ProductNode> findBySimilarProfile(@Param("refId") Long refId, @Param("limit") int limit);

    /**
     * Find random products for discovery/surprise feature.
     * Uses random ordering to provide variety.
     */
    @Query("MATCH (p:Product) " +
           "WHERE (p.deleted IS NULL OR p.deleted = false) " +
           "AND p.flavorProfile IS NOT NULL " +
           "WITH p, rand() as r " +
           "ORDER BY r " +
           "LIMIT $limit " +
           "MATCH (p)-[rel]-(related) " +
           "RETURN p, collect(rel), collect(related)")
    List<ProductNode> findRandomProducts(@Param("limit") int limit);
}
