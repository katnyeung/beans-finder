package com.coffee.beansfinder.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * User-submitted brand suggestions for review before enrichment.
 * Prevents spam and unnecessary AI costs.
 */
@Entity
@Table(name = "brand_suggestions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "website_url", nullable = false, length = 500)
    private String websiteUrl;

    @Column(name = "submitted_by_ip", length = 45)
    private String submittedByIp;

    @Column(length = 50)
    @Builder.Default
    private String status = "pending";

    @Column(name = "reviewer_notes", columnDefinition = "TEXT")
    private String reviewerNotes;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;
}
