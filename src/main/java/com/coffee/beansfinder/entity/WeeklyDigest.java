package com.coffee.beansfinder.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "weekly_digests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyDigest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "week_end", nullable = false)
    private LocalDate weekEnd;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String narrative;

    @Type(JsonBinaryType.class)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metrics;

    @Type(JsonBinaryType.class)
    @Column(name = "news_article_ids", columnDefinition = "jsonb")
    private String newsArticleIds;

    @Column(name = "generated_at", nullable = false)
    @Builder.Default
    private LocalDateTime generatedAt = LocalDateTime.now();

    @Column(name = "generation_cost")
    private BigDecimal generationCost;

    @Column(length = 20)
    @Builder.Default
    private String status = "draft";

    @PrePersist
    protected void onCreate() {
        if (generatedAt == null) {
            generatedAt = LocalDateTime.now();
        }
    }
}
