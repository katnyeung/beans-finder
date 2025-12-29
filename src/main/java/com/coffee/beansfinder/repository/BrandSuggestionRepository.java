package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.BrandSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BrandSuggestionRepository extends JpaRepository<BrandSuggestion, Long> {

    List<BrandSuggestion> findByStatusOrderByCreatedAtDesc(String status);

    List<BrandSuggestion> findAllByOrderByCreatedAtDesc();

    boolean existsByWebsiteUrlIgnoreCase(String websiteUrl);

    boolean existsByNameIgnoreCase(String name);
}
