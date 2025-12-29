package com.coffee.beansfinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "price_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "brand_id")
    private Long brandId;

    @Column(length = 100)
    private String origin;

    private BigDecimal price;

    @Column(length = 3)
    @Builder.Default
    private String currency = "GBP";

    @Column(name = "bag_size", length = 50)
    private String bagSize;

    @Column(name = "price_per_100g")
    private BigDecimal pricePer100g;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @PrePersist
    protected void onCreate() {
        if (recordedAt == null) {
            recordedAt = LocalDateTime.now();
        }
    }
}
