package com.coffee.beansfinder.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "coffee_news")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoffeeNews {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, length = 1000)
    private String link;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private String themes;

    @Column(length = 20)
    private String sentiment;

    @Column(name = "relevance_score")
    private BigDecimal relevanceScore;

    @Column(name = "fetched_at", nullable = false)
    @Builder.Default
    private LocalDateTime fetchedAt = LocalDateTime.now();

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @PrePersist
    protected void onCreate() {
        if (fetchedAt == null) {
            fetchedAt = LocalDateTime.now();
        }
    }
}
