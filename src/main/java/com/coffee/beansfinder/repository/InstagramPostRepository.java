package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.InstagramPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InstagramPostRepository extends JpaRepository<InstagramPost, Long> {

    List<InstagramPost> findByStatus(String status);

    List<InstagramPost> findByStatusOrderByCreatedAtDesc(String status);

    List<InstagramPost> findByStatusAndScheduledAtBefore(String status, LocalDateTime time);

    List<InstagramPost> findByStatusOrderByScheduledAtAsc(String status);

    List<InstagramPost> findByStatusOrderByPostedAtDesc(String status);

    boolean existsByContentHash(String contentHash);
}
