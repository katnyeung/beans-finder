package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.UserProductTracking;
import com.coffee.beansfinder.entity.UserProductTracking.TrackingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserProductTrackingRepository extends JpaRepository<UserProductTracking, Long> {

    // Find user's tracking for a specific product with product and brand eagerly fetched
    @Query("SELECT t FROM UserProductTracking t " +
           "LEFT JOIN FETCH t.product p " +
           "LEFT JOIN FETCH p.brand " +
           "WHERE t.user.id = :userId AND t.product.id = :productId")
    Optional<UserProductTracking> findByUserIdAndProductId(@Param("userId") Long userId, @Param("productId") Long productId);

    // Find all tracking entries for a user with product and brand eagerly fetched
    @Query("SELECT t FROM UserProductTracking t " +
           "LEFT JOIN FETCH t.product p " +
           "LEFT JOIN FETCH p.brand " +
           "WHERE t.user.id = :userId ORDER BY t.updatedAt DESC")
    List<UserProductTracking> findByUserIdOrderByUpdatedAtDesc(@Param("userId") Long userId);

    // Find by user and status with product and brand eagerly fetched
    @Query("SELECT t FROM UserProductTracking t " +
           "LEFT JOIN FETCH t.product p " +
           "LEFT JOIN FETCH p.brand " +
           "WHERE t.user.id = :userId AND t.status = :status ORDER BY t.updatedAt DESC")
    List<UserProductTracking> findByUserIdAndStatusOrderByUpdatedAtDesc(@Param("userId") Long userId, @Param("status") TrackingStatus status);

    // Delete user's tracking for a product
    void deleteByUserIdAndProductId(Long userId, Long productId);

    // Count how many users love a product
    @Query("SELECT COUNT(t) FROM UserProductTracking t WHERE t.product.id = :productId AND t.status = 'LOVE'")
    long countLovesByProductId(@Param("productId") Long productId);

    // Count total ratings for a product
    @Query("SELECT COUNT(t) FROM UserProductTracking t WHERE t.product.id = :productId AND t.rating IS NOT NULL")
    long countRatingsByProductId(@Param("productId") Long productId);

    // Average rating for a product
    @Query("SELECT AVG(t.rating) FROM UserProductTracking t WHERE t.product.id = :productId AND t.rating IS NOT NULL")
    Double getAverageRatingByProductId(@Param("productId") Long productId);

    // Get product stats (love count, avg rating, rating count) in one query
    @Query("SELECT " +
           "COUNT(CASE WHEN t.status = 'LOVE' THEN 1 END) as loveCount, " +
           "AVG(t.rating) as avgRating, " +
           "COUNT(t.rating) as ratingCount " +
           "FROM UserProductTracking t WHERE t.product.id = :productId")
    Object[] getProductStats(@Param("productId") Long productId);
}
