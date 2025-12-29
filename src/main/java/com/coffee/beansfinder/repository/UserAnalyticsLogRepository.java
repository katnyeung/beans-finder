package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.UserAnalyticsLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for user analytics logs.
 * Provides queries for generating sales reports.
 */
@Repository
public interface UserAnalyticsLogRepository extends JpaRepository<UserAnalyticsLog, Long> {

    /**
     * Count actions by type for a brand within a date range
     */
    @Query("SELECT COUNT(l) FROM UserAnalyticsLog l WHERE l.brandId = :brandId AND l.actionType = :actionType AND l.createdAt BETWEEN :from AND :to")
    long countByBrandAndActionType(
            @Param("brandId") Long brandId,
            @Param("actionType") String actionType,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /**
     * Count actions by type for a product within a date range
     */
    @Query("SELECT COUNT(l) FROM UserAnalyticsLog l WHERE l.productId = :productId AND l.actionType = :actionType AND l.createdAt BETWEEN :from AND :to")
    long countByProductAndActionType(
            @Param("productId") Long productId,
            @Param("actionType") String actionType,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /**
     * Get all logs for a brand within a date range
     */
    List<UserAnalyticsLog> findByBrandIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long brandId, LocalDateTime from, LocalDateTime to
    );

    /**
     * Get recent logs by action type
     */
    List<UserAnalyticsLog> findByActionTypeOrderByCreatedAtDesc(String actionType);

    /**
     * Count total actions by type
     */
    long countByActionType(String actionType);
}
