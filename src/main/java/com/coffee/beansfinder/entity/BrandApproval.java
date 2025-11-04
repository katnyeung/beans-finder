package com.coffee.beansfinder.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "brand_approvals")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    private CoffeeBrand brand;

    @Column(nullable = false)
    private String submittedBy; // User ID or email

    @Column(nullable = false)
    private LocalDateTime submittedDate;

    @Column(nullable = false)
    @Builder.Default
    private String status = "pending"; // pending, approved, rejected

    private String reviewedBy;
    private LocalDateTime reviewedDate;

    @Column(columnDefinition = "TEXT")
    private String submissionNotes;

    @Column(columnDefinition = "TEXT")
    private String reviewNotes;

    // Request details
    private String requestedBrandName;
    private String requestedWebsite;
    private String requestedCountry;

    @Column(columnDefinition = "TEXT")
    private String requestedDescription;

    @PrePersist
    protected void onCreate() {
        submittedDate = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        if (!status.equals("pending") && reviewedDate == null) {
            reviewedDate = LocalDateTime.now();
        }
    }
}
