package com.coffee.beansfinder.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "coffee_products", indexes = {
    @Index(name = "idx_brand", columnList = "brand"),
    @Index(name = "idx_last_update", columnList = "lastUpdateDate"),
    @Index(name = "idx_crawl_status", columnList = "crawlStatus")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CoffeeProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String brand;

    @Column(nullable = false)
    private String productName;

    private String origin;
    private String region;
    private String process;
    private String producer;
    private String variety;
    private String altitude;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<String> tastingNotes = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> scaFlavors = new HashMap<>();

    private String sellerUrl;

    private BigDecimal price;

    @Column(length = 3)
    @Builder.Default
    private String currency = "GBP";

    @Builder.Default
    private Boolean inStock = true;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime lastUpdateDate = LocalDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private String crawlStatus = "pending";

    @Column(columnDefinition = "TEXT")
    private String rawDescription;

    @PrePersist
    protected void onCreate() {
        if (lastUpdateDate == null) {
            lastUpdateDate = LocalDateTime.now();
        }
        if (crawlStatus == null) {
            crawlStatus = "pending";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        lastUpdateDate = LocalDateTime.now();
    }
}
