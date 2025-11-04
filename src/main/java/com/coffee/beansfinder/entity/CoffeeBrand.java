package com.coffee.beansfinder.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "coffee_brands")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoffeeBrand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String website;
    private String country;

    @Column(nullable = false)
    @Builder.Default
    private String status = "active"; // active, inactive, pending_approval

    @Column(nullable = false)
    private Boolean approved = false;

    private LocalDateTime approvedDate;
    private String approvedBy;

    @Column(nullable = false)
    private LocalDateTime createdDate;

    private LocalDateTime lastCrawlDate;

    @Column(columnDefinition = "TEXT")
    private String crawlConfig; // JSON configuration for crawler

    // Crawling settings
    private Integer crawlIntervalDays = 14;
    private String robotsTxtUrl;
    private Boolean respectRobotsTxt = true;

    @JsonIgnore
    @OneToMany(mappedBy = "brand", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CoffeeProduct> products = new ArrayList<>();

    // JSONB array of product IDs for denormalized access
    @Type(JsonBinaryType.class)
    @Column(name = "products", columnDefinition = "jsonb", insertable = false, updatable = false)
    private String productsJson;

    // JSONB array for user suggestions
    @Type(JsonBinaryType.class)
    @Column(name = "user_suggestions", columnDefinition = "jsonb")
    @Builder.Default
    private String userSuggestions = "[]";

    @PrePersist
    protected void onCreate() {
        createdDate = LocalDateTime.now();
        if (status == null) {
            status = "pending_approval";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        if (approved && approvedDate == null) {
            approvedDate = LocalDateTime.now();
        }
    }
}
