package com.coffee.beansfinder.dto;

/**
 * DTO for flavor wheel data aggregation
 */
public class FlavorCountDTO {
    private String category;
    private String flavorName;
    private Long productCount;

    public FlavorCountDTO() {
    }

    public FlavorCountDTO(String category, String flavorName, Long productCount) {
        this.category = category;
        this.flavorName = flavorName;
        this.productCount = productCount;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getFlavorName() {
        return flavorName;
    }

    public void setFlavorName(String flavorName) {
        this.flavorName = flavorName;
    }

    public Long getProductCount() {
        return productCount;
    }

    public void setProductCount(Long productCount) {
        this.productCount = productCount;
    }
}
