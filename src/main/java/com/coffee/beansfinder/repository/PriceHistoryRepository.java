package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    /**
     * Get price history for a single product (for chart display)
     * Ordered by time ascending for chart rendering
     */
    List<PriceHistory> findByProductIdOrderByRecordedAtAsc(Long productId);

    /**
     * Get price history within date range for a product
     */
    List<PriceHistory> findByProductIdAndRecordedAtAfterOrderByRecordedAtAsc(
            Long productId, LocalDateTime after);

    /**
     * Get latest price for a product
     */
    @Query("SELECT ph FROM PriceHistory ph WHERE ph.productId = :productId " +
            "ORDER BY ph.recordedAt DESC LIMIT 1")
    PriceHistory findLatestByProductId(@Param("productId") Long productId);

    /**
     * Count price records for a product (to check if chart should be shown)
     */
    long countByProductId(Long productId);

    /**
     * Get average price per 100g by origin for a date range (for future B2B reports)
     */
    @Query(value = "SELECT origin, AVG(price_per_100g) as avg_price, COUNT(*) as count " +
            "FROM price_history " +
            "WHERE origin IS NOT NULL AND recorded_at > :since " +
            "GROUP BY origin ORDER BY avg_price DESC",
            nativeQuery = true)
    List<Object[]> findAveragePriceByOriginSince(@Param("since") LocalDateTime since);

    /**
     * Get average price per 100g by brand for a date range (for future B2B reports)
     */
    @Query(value = "SELECT brand_id, AVG(price_per_100g) as avg_price, COUNT(*) as count " +
            "FROM price_history " +
            "WHERE brand_id IS NOT NULL AND recorded_at > :since " +
            "GROUP BY brand_id ORDER BY avg_price DESC",
            nativeQuery = true)
    List<Object[]> findAveragePriceByBrandSince(@Param("since") LocalDateTime since);
}
