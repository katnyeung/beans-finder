package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandDiscountDTO {
    private Long id;
    private Long brandId;
    private String brandName;
    private String brandWebsite;
    private String title;
    private String description;
    private String discountCode;
    private LocalDateTime createdAt;
}
