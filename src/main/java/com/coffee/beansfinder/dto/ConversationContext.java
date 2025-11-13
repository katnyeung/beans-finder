package com.coffee.beansfinder.dto;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Conversation context for multi-turn chatbot interactions
 * Persisted in PostgreSQL for conversation continuity across sessions
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chatbot_conversations")
public class ConversationContext {
    /**
     * Unique conversation ID
     */
    @Id
    @Column(name = "conversation_id")
    private String conversationId;

    /**
     * Reference product ID (product user is currently exploring)
     */
    @Column(name = "reference_product_id")
    private Long referenceProductId;

    /**
     * Message history (for Grok API + UI display)
     * Format: List of {role: "user"|"assistant", content: "message", products: [{id, name, brand, ...}]}
     * The "products" field is optional and only present in assistant messages
     * Stored as JSONB in PostgreSQL
     */
    @Type(JsonBinaryType.class)
    @Column(name = "messages", columnDefinition = "jsonb")
    private List<Map<String, Object>> messages;

    /**
     * Products already shown to user (to avoid duplicates)
     * Stored as JSONB array
     */
    @Type(JsonBinaryType.class)
    @Column(name = "shown_product_ids", columnDefinition = "jsonb")
    private List<Long> shownProductIds;

    /**
     * Conversation creation time
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Last activity time (for TTL cleanup)
     */
    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt;

    public ConversationContext(String conversationId) {
        this.conversationId = conversationId;
        this.messages = new ArrayList<>();
        this.shownProductIds = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.lastActivityAt = LocalDateTime.now();
    }

    /**
     * Add user message to history
     */
    public void addUserMessage(String message) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.add(Map.of("role", "user", "content", message));
        this.lastActivityAt = LocalDateTime.now();
    }

    /**
     * Add assistant message to history (text only)
     */
    public void addAssistantMessage(String message) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.add(Map.of("role", "assistant", "content", message));
        this.lastActivityAt = LocalDateTime.now();
    }

    /**
     * Add assistant message with product recommendations
     */
    public void addAssistantMessageWithProducts(String message, List<Map<String, Object>> products) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        Map<String, Object> assistantMessage = new java.util.HashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", message);
        assistantMessage.put("products", products);
        this.messages.add(assistantMessage);
        this.lastActivityAt = LocalDateTime.now();
    }

    /**
     * Attach products to the last assistant message (for conversation caching)
     */
    public void attachProductsToLastMessage(List<Map<String, Object>> products) {
        if (this.messages == null || this.messages.isEmpty()) {
            return;
        }
        // Find the last assistant message
        for (int i = this.messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = this.messages.get(i);
            if ("assistant".equals(msg.get("role"))) {
                // Make it mutable and add products
                Map<String, Object> mutableMsg = new java.util.HashMap<>(msg);
                mutableMsg.put("products", products);
                this.messages.set(i, mutableMsg);
                this.lastActivityAt = LocalDateTime.now();
                return;
            }
        }
    }

    /**
     * Update reference product
     */
    public void updateReferenceProduct(Long productId) {
        this.referenceProductId = productId;
        this.lastActivityAt = LocalDateTime.now();
    }

    /**
     * Add shown product ID to avoid duplicates
     */
    public void addShownProduct(Long productId) {
        if (this.shownProductIds == null) {
            this.shownProductIds = new ArrayList<>();
        }
        if (!this.shownProductIds.contains(productId)) {
            this.shownProductIds.add(productId);
        }
    }

    /**
     * Check if product has been shown already
     */
    public boolean hasShownProduct(Long productId) {
        return this.shownProductIds != null && this.shownProductIds.contains(productId);
    }

    /**
     * Update last activity timestamp
     */
    @PreUpdate
    @PrePersist
    public void updateTimestamp() {
        this.lastActivityAt = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
