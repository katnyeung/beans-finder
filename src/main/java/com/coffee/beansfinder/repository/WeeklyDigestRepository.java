package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.WeeklyDigest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WeeklyDigestRepository extends JpaRepository<WeeklyDigest, Long> {

    Optional<WeeklyDigest> findByWeekStart(LocalDate weekStart);

    @Query("SELECT d FROM WeeklyDigest d WHERE d.weekStart = :weekStart ORDER BY d.version DESC LIMIT 1")
    Optional<WeeklyDigest> findLatestVersionByWeekStart(@Param("weekStart") LocalDate weekStart);

    @Query("SELECT COALESCE(MAX(d.version), 0) FROM WeeklyDigest d WHERE d.weekStart = :weekStart")
    Integer findMaxVersionByWeekStart(@Param("weekStart") LocalDate weekStart);

    List<WeeklyDigest> findByWeekStartOrderByVersionDesc(LocalDate weekStart);

    @Query("SELECT d FROM WeeklyDigest d WHERE d.status = :status ORDER BY d.weekStart DESC LIMIT 1")
    Optional<WeeklyDigest> findLatestByStatus(@Param("status") String status);

    List<WeeklyDigest> findByStatusOrderByWeekStartDesc(String status);

    @Query("SELECT d FROM WeeklyDigest d ORDER BY d.weekStart DESC")
    List<WeeklyDigest> findAllOrderByWeekStartDesc();

    @Query("SELECT d FROM WeeklyDigest d ORDER BY d.weekStart DESC LIMIT :limit")
    List<WeeklyDigest> findRecentDigests(@Param("limit") int limit);
}
