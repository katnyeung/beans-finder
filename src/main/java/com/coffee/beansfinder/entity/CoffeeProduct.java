package com.coffee.beansfinder.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "coffee_products")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoffeeProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    private CoffeeBrand brand;

    @Column(nullable = false)
    private String productName;

    private String origin;
    private String region;
    private String process;
    private String producer;
    private String variety;
    private String altitude;
    private String roastLevel; // Light, Medium, Dark, Omni, Unknown

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private String tastingNotesJson; // JSON array as string - stored as JSONB in PostgreSQL

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private String scaFlavorsJson; // JSON object as string - stored as JSONB in PostgreSQL

    private String sellerUrl;
    private BigDecimal price;

    @Column(length = 3)
    private String currency = "GBP";

    private Boolean inStock = true;

    @Column(nullable = false)
    private LocalDateTime lastUpdateDate;

    @Column(nullable = false)
    private String crawlStatus = "pending"; // pending, in_progress, done, error

    @Column(columnDefinition = "TEXT")
    private String rawDescription;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    protected void onCreate() {
        lastUpdateDate = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        lastUpdateDate = LocalDateTime.now();
    }
}
