package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.BrandApproval;
import com.coffee.beansfinder.entity.CoffeeBrand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrandApprovalRepository extends JpaRepository<BrandApproval, Long> {

    /**
     * Find approvals by status
     */
    List<BrandApproval> findByStatus(String status);

    /**
     * Find all pending approvals
     */
    List<BrandApproval> findByStatusOrderBySubmittedDateAsc(String status);

    /**
     * Find approval by brand
     */
    Optional<BrandApproval> findByBrand(CoffeeBrand brand);

    /**
     * Find approvals submitted by user
     */
    List<BrandApproval> findBySubmittedBy(String submittedBy);
}
