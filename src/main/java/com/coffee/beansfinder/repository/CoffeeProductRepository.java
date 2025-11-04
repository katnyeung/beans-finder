package com.coffee.beansfinder.repository;

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

    List<CoffeeProduct> findByBrand(String brand);

    Optional<CoffeeProduct> findByBrandAndProductName(String brand, String productName);

    @Query("SELECT p FROM CoffeeProduct p WHERE p.lastUpdateDate < :cutoffDate AND p.crawlStatus != 'in_progress'")
    List<CoffeeProduct> findProductsNeedingUpdate(@Param("cutoffDate") LocalDateTime cutoffDate);

    @Query("SELECT DISTINCT p.brand FROM CoffeeProduct p ORDER BY p.brand")
    List<String> findAllBrands();

    List<CoffeeProduct> findByOrigin(String origin);

    List<CoffeeProduct> findByProcess(String process);

    List<CoffeeProduct> findByVariety(String variety);

    @Query("SELECT p FROM CoffeeProduct p WHERE p.inStock = true")
    List<CoffeeProduct> findInStockProducts();
}
