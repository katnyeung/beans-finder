package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.entity.CoffeeProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CoffeeProductRepository extends JpaRepository<CoffeeProduct, Long> {

    /**
     * Find products that need updating (older than specified days)
     */
    @Query("SELECT c FROM CoffeeProduct c WHERE c.lastUpdateDate < :cutoffDate")
    List<CoffeeProduct> findProductsNeedingUpdate(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Find products by brand
     */
    List<CoffeeProduct> findByBrand(CoffeeBrand brand);

    /**
     * Find products by brand ID with brand eagerly fetched
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.brand.id = :brandId")
    List<CoffeeProduct> findByBrandId(@Param("brandId") Long brandId);

    /**
     * Find products by origin (country) - case sensitive
     */
    List<CoffeeProduct> findByOrigin(String origin);

    /**
     * Find products by origin (country) - case insensitive
     * Eagerly fetches brand to avoid LazyInitializationException
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE LOWER(p.origin) = LOWER(:origin)")
    List<CoffeeProduct> findByOriginIgnoreCase(@Param("origin") String origin);

    /**
     * Find products by region (exact match, case-insensitive)
     * Eagerly fetches brand to avoid LazyInitializationException
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE LOWER(p.region) = LOWER(:region)")
    List<CoffeeProduct> findByRegionIgnoreCase(@Param("region") String region);

    /**
     * Find products by region containing (partial match, case-insensitive)
     * Eagerly fetches brand to avoid LazyInitializationException
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE LOWER(p.region) LIKE LOWER(CONCAT('%', :region, '%'))")
    List<CoffeeProduct> findByRegionContainingIgnoreCase(@Param("region") String region);

    /**
     * Find products by origin (country) and region (exact match)
     */
    List<CoffeeProduct> findByOriginAndRegionIgnoreCase(String origin, String region);

    /**
     * Find products by process with brand eagerly fetched
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.process = :process")
    List<CoffeeProduct> findByProcess(@Param("process") String process);

    /**
     * Find products by crawl status with brand eagerly fetched
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.crawlStatus = :status")
    List<CoffeeProduct> findByCrawlStatus(@Param("status") String status);

    /**
     * Find product by brand and product name
     */
    Optional<CoffeeProduct> findByBrandAndProductName(CoffeeBrand brand, String productName);

    /**
     * Find product by seller URL (for updating existing products during re-crawl)
     */
    Optional<CoffeeProduct> findBySellerUrl(String sellerUrl);

    /**
     * Check if product exists
     */
    boolean existsByBrandAndProductName(CoffeeBrand brand, String productName);

    /**
     * Find products by variety with brand eagerly fetched
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.variety = :variety")
    List<CoffeeProduct> findByVariety(@Param("variety") String variety);

    /**
     * Find in-stock products with brand eagerly fetched
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.inStock = true")
    List<CoffeeProduct> findByInStockTrue();

    /**
     * Count products by brand ID
     */
    long countByBrandId(Long brandId);

    /**
     * Count products by brand ID - only valid coffee products with tasting notes (excludes deleted)
     */
    @Query(value = "SELECT COUNT(*) FROM coffee_products p WHERE p.brand_id = :brandId " +
           "AND p.deleted_at IS NULL " +
           "AND p.tasting_notes_json IS NOT NULL " +
           "AND p.tasting_notes_json::text <> '' " +
           "AND p.tasting_notes_json::text <> '[]' " +
           "AND p.tasting_notes_json::text <> 'null'", nativeQuery = true)
    long countValidProductsByBrandId(@Param("brandId") Long brandId);

    /**
     * Find products by brand ID - only valid coffee products with tasting notes (excludes deleted)
     * Note: Uses native query for JSONB comparison. Brand is NOT eagerly fetched -
     * caller already knows brand from brandId parameter.
     */
    @Query(value = "SELECT * FROM coffee_products p WHERE p.brand_id = :brandId " +
           "AND p.deleted_at IS NULL " +
           "AND p.tasting_notes_json IS NOT NULL " +
           "AND p.tasting_notes_json::text <> '' " +
           "AND p.tasting_notes_json::text <> '[]' " +
           "AND p.tasting_notes_json::text <> 'null'", nativeQuery = true)
    List<CoffeeProduct> findValidProductsByBrandId(@Param("brandId") Long brandId);

    /**
     * Batch count products by multiple brand IDs (excludes deleted)
     * Returns a list of projections with brandId and count
     */
    @Query("SELECT p.brand.id as brandId, COUNT(p) as count " +
           "FROM CoffeeProduct p " +
           "WHERE p.brand.id IN :brandIds AND p.deletedAt IS NULL " +
           "GROUP BY p.brand.id")
    List<BrandProductCount> countByBrandIds(@Param("brandIds") List<Long> brandIds);

    /**
     * Batch count valid products (with tasting notes) by multiple brand IDs (excludes deleted)
     */
    @Query(value = "SELECT p.brand_id as brandId, COUNT(*) as count " +
           "FROM coffee_products p " +
           "WHERE p.brand_id IN :brandIds " +
           "AND p.deleted_at IS NULL " +
           "AND p.tasting_notes_json IS NOT NULL " +
           "AND p.tasting_notes_json::text <> '' " +
           "AND p.tasting_notes_json::text <> '[]' " +
           "AND p.tasting_notes_json::text <> 'null' " +
           "GROUP BY p.brand_id", nativeQuery = true)
    List<BrandProductCount> countValidProductsByBrandIds(@Param("brandIds") List<Long> brandIds);

    /**
     * Batch count products by origins (excludes deleted)
     * Returns a list of projections with origin and count
     */
    @Query("SELECT p.origin as origin, COUNT(p) as count " +
           "FROM CoffeeProduct p " +
           "WHERE p.origin IS NOT NULL AND p.deletedAt IS NULL " +
           "GROUP BY p.origin")
    List<OriginProductCount> countByOrigins();

    /**
     * Projection interface for brand product counts
     */
    interface BrandProductCount {
        Long getBrandId();
        Long getCount();
    }

    /**
     * Projection interface for origin product counts
     */
    interface OriginProductCount {
        String getOrigin();
        Long getCount();
    }

    // ===== New methods for incremental crawling =====

    /**
     * Find products created after a specific date (new products)
     * Ordered by createdDate descending (newest first)
     * Eagerly fetches brand to avoid LazyInitializationException
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.createdDate > :cutoffDate ORDER BY p.createdDate DESC")
    List<CoffeeProduct> findByCreatedDateAfterOrderByCreatedDateDesc(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Find products updated after a specific date
     * Ordered by lastUpdateDate descending (most recently updated first)
     * Eagerly fetches brand to avoid LazyInitializationException
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.lastUpdateDate > :cutoffDate ORDER BY p.lastUpdateDate DESC")
    List<CoffeeProduct> findByLastUpdateDateAfterOrderByLastUpdateDateDesc(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Find all products with brand eagerly fetched (for trending cache rebuild)
     * Avoids LazyInitializationException in async contexts
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand")
    List<CoffeeProduct> findAllWithBrand();

    /**
     * Find product by ID with brand eagerly fetched
     * Avoids LazyInitializationException when accessing brand outside session
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.id = :id")
    Optional<CoffeeProduct> findByIdWithBrand(@Param("id") Long id);

    /**
     * Find product by brand and seller URL (for efficient lookup during crawl)
     */
    Optional<CoffeeProduct> findByBrandAndSellerUrl(CoffeeBrand brand, String sellerUrl);

    /**
     * Find all products for a brand as a map keyed by sellerUrl
     * (Used for efficient lookup during incremental crawl)
     */
    @Query("SELECT p FROM CoffeeProduct p WHERE p.brand = :brand")
    List<CoffeeProduct> findAllByBrand(@Param("brand") CoffeeBrand brand);

    // ===== Admin update request methods =====

    /**
     * Find products flagged for update
     * Eagerly fetches brand to avoid LazyInitializationException
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.updateRequested = true")
    List<CoffeeProduct> findByUpdateRequestedTrue();

    /**
     * Count products flagged for update
     */
    long countByUpdateRequestedTrue();

    // ===== Search methods =====

    /**
     * Search products by name (case-insensitive, partial match)
     */
    List<CoffeeProduct> findByProductNameContainingIgnoreCase(String productName);

    /**
     * Search products by product name OR brand name (case-insensitive, partial match)
     */
    @Query("SELECT p FROM CoffeeProduct p WHERE " +
           "LOWER(p.productName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.brand.name) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<CoffeeProduct> searchByProductOrBrandName(@Param("query") String query);

    // ===== Force re-crawl methods =====

    /**
     * Clear content hashes for a brand to force OpenAI re-extraction
     * Returns number of products updated
     */
    @Modifying
    @Transactional
    @Query("UPDATE CoffeeProduct p SET p.contentHash = NULL WHERE p.brand.id = :brandId")
    int clearContentHashByBrandId(@Param("brandId") Long brandId);

    // ===== Soft delete methods =====

    /**
     * Find active (non-deleted) products by brand
     */
    List<CoffeeProduct> findByBrandAndDeletedAtIsNull(CoffeeBrand brand);

    /**
     * Find active (non-deleted) products by brand ID
     */
    List<CoffeeProduct> findByBrandIdAndDeletedAtIsNull(Long brandId);

    /**
     * Find product by seller URL including soft-deleted products (for recovery)
     */
    @Query("SELECT p FROM CoffeeProduct p WHERE p.sellerUrl = :sellerUrl")
    Optional<CoffeeProduct> findBySellerUrlIncludeDeleted(@Param("sellerUrl") String sellerUrl);

    /**
     * Find all active products with brand eagerly fetched (WARNING: loads all into memory)
     * Use findActiveProductsPaginated for large datasets
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.deletedAt IS NULL")
    List<CoffeeProduct> findByDeletedAtIsNull();

    /**
     * Find active products with pagination (memory-safe)
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.deletedAt IS NULL ORDER BY p.id")
    List<CoffeeProduct> findActiveProductsPaginated(org.springframework.data.domain.Pageable pageable);

    /**
     * Find all soft-deleted products
     */
    List<CoffeeProduct> findByDeletedAtIsNotNull();

    /**
     * Count active products by brand ID
     */
    long countByBrandIdAndDeletedAtIsNull(Long brandId);

    /**
     * Find active products by origin with brand eagerly fetched
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.deletedAt IS NULL AND LOWER(p.origin) = LOWER(:origin)")
    List<CoffeeProduct> findByOriginIgnoreCaseAndDeletedAtIsNull(@Param("origin") String origin);

    /**
     * Search active products by name
     */
    List<CoffeeProduct> findByProductNameContainingIgnoreCaseAndDeletedAtIsNull(String productName);

    /**
     * Search active products by product name OR brand name
     * Eagerly fetches brand to avoid LazyInitializationException
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.deletedAt IS NULL AND (" +
           "LOWER(p.productName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.brand.name) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<CoffeeProduct> searchActiveByProductOrBrandName(@Param("query") String query);

    /**
     * Find all active products with brand eagerly fetched
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand WHERE p.deletedAt IS NULL")
    List<CoffeeProduct> findAllActiveWithBrand();

    /**
     * Count active products by origins
     */
    @Query("SELECT p.origin as origin, COUNT(p) as count " +
           "FROM CoffeeProduct p " +
           "WHERE p.origin IS NOT NULL AND p.deletedAt IS NULL " +
           "GROUP BY p.origin")
    List<OriginProductCount> countActiveByOrigins();

    // ===== Efficient filtered queries for DiscoverController (avoid loading all products) =====

    /**
     * Find active products by process (partial match, case-insensitive) with brand eager fetch
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand " +
           "WHERE p.deletedAt IS NULL AND LOWER(p.process) LIKE LOWER(CONCAT('%', :process, '%'))")
    List<CoffeeProduct> findActiveByProcessContainingWithBrand(@Param("process") String process);

    /**
     * Find active products by variety (partial match, case-insensitive) with brand eager fetch
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand " +
           "WHERE p.deletedAt IS NULL AND LOWER(p.variety) LIKE LOWER(CONCAT('%', :variety, '%'))")
    List<CoffeeProduct> findActiveByVarietyContainingWithBrand(@Param("variety") String variety);

    /**
     * Find active products by roast level (exact match, case-insensitive) with brand eager fetch
     */
    @Query("SELECT p FROM CoffeeProduct p LEFT JOIN FETCH p.brand " +
           "WHERE p.deletedAt IS NULL AND LOWER(p.roastLevel) = LOWER(:roastLevel)")
    List<CoffeeProduct> findActiveByRoastLevelWithBrand(@Param("roastLevel") String roastLevel);
}
