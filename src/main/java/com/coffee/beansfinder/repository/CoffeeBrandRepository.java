package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.CoffeeBrand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CoffeeBrandRepository extends JpaRepository<CoffeeBrand, Long> {

    /**
     * Find brand by name
     */
    Optional<CoffeeBrand> findByName(String name);

    /**
     * Check if brand exists by name
     */
    boolean existsByName(String name);

    /**
     * Find all approved brands
     */
    List<CoffeeBrand> findByApprovedTrue();

    /**
     * Find all pending (not approved) brands
     */
    List<CoffeeBrand> findByApprovedFalse();

    /**
     * Find brands by status
     */
    List<CoffeeBrand> findByStatus(String status);

    /**
     * Find brands needing crawl (approved brands older than their interval)
     */
    @Query("SELECT b FROM CoffeeBrand b WHERE b.approved = true " +
           "AND (b.lastCrawlDate IS NULL OR b.lastCrawlDate < :cutoffDate)")
    List<CoffeeBrand> findBrandsNeedingCrawl(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Find brands by country
     */
    List<CoffeeBrand> findByCountry(String country);
}
