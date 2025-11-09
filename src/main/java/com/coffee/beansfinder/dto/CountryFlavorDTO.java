package com.coffee.beansfinder.dto;

import java.util.List;

/**
 * DTO for country-level flavor aggregation on map
 */
public class CountryFlavorDTO {
    private String countryCode;
    private String countryName;
    private List<FlavorInfo> topFlavors;

    public CountryFlavorDTO() {
    }

    public CountryFlavorDTO(String countryCode, String countryName, List<FlavorInfo> topFlavors) {
        this.countryCode = countryCode;
        this.countryName = countryName;
        this.topFlavors = topFlavors;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    public String getCountryName() {
        return countryName;
    }

    public void setCountryName(String countryName) {
        this.countryName = countryName;
    }

    public List<FlavorInfo> getTopFlavors() {
        return topFlavors;
    }

    public void setTopFlavors(List<FlavorInfo> topFlavors) {
        this.topFlavors = topFlavors;
    }

    /**
     * Inner class for individual flavor info
     */
    public static class FlavorInfo {
        private String flavor;
        private String category;
        private Long productCount;
        private Double percentage;

        public FlavorInfo() {
        }

        public FlavorInfo(String flavor, String category, Long productCount, Double percentage) {
            this.flavor = flavor;
            this.category = category;
            this.productCount = productCount;
            this.percentage = percentage;
        }

        public String getFlavor() {
            return flavor;
        }

        public void setFlavor(String flavor) {
            this.flavor = flavor;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public Long getProductCount() {
            return productCount;
        }

        public void setProductCount(Long productCount) {
            this.productCount = productCount;
        }

        public Double getPercentage() {
            return percentage;
        }

        public void setPercentage(Double percentage) {
            this.percentage = percentage;
        }
    }
}
