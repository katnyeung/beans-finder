package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.entity.CoffeeProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
     * Find products by brand ID
     */
    List<CoffeeProduct> findByBrandId(Long brandId);

    /**
     * Find products by origin
     */
    List<CoffeeProduct> findByOrigin(String origin);

    /**
     * Find products by process
     */
    List<CoffeeProduct> findByProcess(String process);

    /**
     * Find products by crawl status
     */
    List<CoffeeProduct> findByCrawlStatus(String status);

    /**
     * Find product by brand and product name
     */
    Optional<CoffeeProduct> findByBrandAndProductName(CoffeeBrand brand, String productName);

    /**
     * Check if product exists
     */
    boolean existsByBrandAndProductName(CoffeeBrand brand, String productName);

    /**
     * Find products by variety
     */
    List<CoffeeProduct> findByVariety(String variety);

    /**
     * Find in-stock products
     */
    List<CoffeeProduct> findByInStockTrue();
}
