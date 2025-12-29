package com.coffee.beansfinder.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;

/**
 * Entity for tracking user interactions for sales analytics.
 * Used to generate reports for coffee brands showing views, clicks, and recommendations.
 */
@Entity
@Table(name = "user_analytics_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAnalyticsLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "brand_id")
    private Long brandId;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Type(JsonBinaryType.class)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    // Action type constants
    public static final String ACTION_PRODUCT_VIEW = "product_view";
    public static final String ACTION_SELLER_CLICK = "seller_click";
    public static final String ACTION_CHAT_QUESTION = "chat_question";
    public static final String ACTION_CHAT_ANSWER = "chat_answer";
}
