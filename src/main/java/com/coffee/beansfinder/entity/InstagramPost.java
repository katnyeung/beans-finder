package com.coffee.beansfinder.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "instagram_posts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstagramPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String caption;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "image_prompt", columnDefinition = "TEXT")
    private String imagePrompt;

    @Column(name = "source_type", length = 50)
    private String sourceType;

    @Type(JsonBinaryType.class)
    @Column(name = "source_ids", columnDefinition = "jsonb")
    private String sourceIds;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "generation_cost", precision = 6, scale = 4)
    private BigDecimal generationCost;

    @Column(length = 20, nullable = false)
    @Builder.Default
    private String status = "draft";

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "instagram_media_id", length = 100)
    private String instagramMediaId;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
