package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.dto.ConversationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for persistent chatbot conversations
 * Stores conversation state in PostgreSQL with JSONB fields
 */
@Repository
public interface ConversationRepository extends JpaRepository<ConversationContext, String> {

    /**
     * Find conversations by last activity time (for cleanup of old conversations)
     */
    List<ConversationContext> findByLastActivityAtBefore(LocalDateTime threshold);

    /**
     * Delete old conversations (TTL cleanup)
     */
    @Modifying
    @Query("DELETE FROM ConversationContext c WHERE c.lastActivityAt < :threshold")
    void deleteByLastActivityAtBefore(@Param("threshold") LocalDateTime threshold);

    /**
     * Find conversations by reference product
     */
    List<ConversationContext> findByReferenceProductId(Long referenceProductId);
}
