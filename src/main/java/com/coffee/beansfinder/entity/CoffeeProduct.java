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

    @Type(JsonBinaryType.class)
    @Column(name = "price_variants_json", columnDefinition = "jsonb")
    private String priceVariantsJson; // JSON array of {size, price} objects

    /**
     * 9-dimensional flavor profile [0.0-1.0] for SCA category intensities
     * Indices: 0=fruity, 1=floral, 2=sweet, 3=nutty, 4=spices, 5=roasted, 6=green, 7=sour, 8=other
     */
    @Type(JsonBinaryType.class)
    @Column(name = "flavor_profile", columnDefinition = "jsonb")
    private String flavorProfileJson;

    /**
     * 4-dimensional character axes [-1.0 to +1.0] for coffee character spectrum
     * Indices: 0=acidity (flat↔bright), 1=body (light↔full), 2=roast (light↔dark), 3=complexity (clean↔funky)
     */
    @Type(JsonBinaryType.class)
    @Column(name = "character_axes", columnDefinition = "jsonb")
    private String characterAxesJson;

    @Column(length = 3)
    private String currency = "GBP";

    private Boolean inStock = true;

    @Column(length = 64)
    private String contentHash; // SHA-256 hash of Playwright-extracted text for change detection

    private LocalDateTime createdDate; // When product was first discovered (never changes)

    @Column(nullable = false)
    private LocalDateTime lastUpdateDate; // When product content was last updated

    @Column(nullable = false)
    private String crawlStatus = "pending"; // pending, in_progress, done, error

    @Column(columnDefinition = "TEXT")
    private String rawDescription;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    @Builder.Default
    private Boolean updateRequested = false; // Flag for admin to re-crawl

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        lastUpdateDate = now;
        if (createdDate == null) {
            createdDate = now; // Only set on first insert
        }
    }

    @PreUpdate
    protected void onUpdate() {
        lastUpdateDate = LocalDateTime.now();
        // createdDate is never updated - it stays as the original creation time
    }
}
