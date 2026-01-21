package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.InstagramCredentials;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InstagramCredentialsRepository extends JpaRepository<InstagramCredentials, Long> {

    Optional<InstagramCredentials> findByInstagramUserId(String instagramUserId);

    Optional<InstagramCredentials> findFirstByOrderByCreatedAtDesc();

    boolean existsByInstagramUserId(String instagramUserId);
}
