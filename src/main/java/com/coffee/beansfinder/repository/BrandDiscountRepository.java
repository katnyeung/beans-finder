package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.BrandDiscount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface BrandDiscountRepository extends JpaRepository<BrandDiscount, Long> {

    List<BrandDiscount> findByBrandId(Long brandId);

    @Query("SELECT bd FROM BrandDiscount bd JOIN FETCH bd.brand ORDER BY bd.createdAt DESC")
    List<BrandDiscount> findAllWithBrand();

    @Modifying
    @Transactional
    @Query("DELETE FROM BrandDiscount bd WHERE bd.brandId = :brandId")
    void deleteByBrandId(@Param("brandId") Long brandId);
}
