package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.CoffeeNews;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CoffeeNewsRepository extends JpaRepository<CoffeeNews, Long> {

    boolean existsByLink(String link);

    boolean existsByContentHash(String contentHash);

    List<CoffeeNews> findByPublishedAtBetweenOrderByPublishedAtDesc(
            LocalDateTime startDate, LocalDateTime endDate);

    List<CoffeeNews> findBySourceOrderByPublishedAtDesc(String source);

    @Query("SELECT n FROM CoffeeNews n WHERE n.publishedAt >= :since " +
            "AND (n.relevanceScore IS NULL OR n.relevanceScore >= :minRelevance) " +
            "ORDER BY n.publishedAt DESC")
    List<CoffeeNews> findRelevantNewsSince(
            @Param("since") LocalDateTime since,
            @Param("minRelevance") BigDecimal minRelevance);

    List<CoffeeNews> findByIdIn(List<Long> ids);

    @Query("SELECT n FROM CoffeeNews n WHERE n.fetchedAt >= :since ORDER BY n.publishedAt DESC")
    List<CoffeeNews> findRecentlyFetched(@Param("since") LocalDateTime since);

    List<CoffeeNews> findByFetchedAtAfter(LocalDateTime since);
}
