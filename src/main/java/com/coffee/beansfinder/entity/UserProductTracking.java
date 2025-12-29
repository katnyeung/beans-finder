package com.coffee.beansfinder.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * User's tracking status for products (love, bought, want, dislike) with optional rating
 */
@Entity
@Table(name = "user_product_tracking",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "product_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProductTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private CoffeeProduct product;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TrackingStatus status;

    @Column(columnDefinition = "SMALLINT")
    private Short rating;  // 1-5 stars, nullable

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "included_in_chat", nullable = false)
    @Builder.Default
    private Boolean includedInChat = true;  // Default to included

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum TrackingStatus {
        LOVE,      // User loves this coffee
        BOUGHT,    // User has purchased this
        WANT,      // User wants to buy
        DISLIKE    // User doesn't like this
    }
}
