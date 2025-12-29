package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketPulseDTO {
    private int totalProducts;
    private int newProductsThisWeek;
    private List<TrendingItem> trendingOrigins;
    private List<TrendingItem> trendingProcesses;
    private LocalDate snapshotDate;
    private String digestUrl;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendingItem {
        private String name;
        private int count;
        private int changeFromLastWeek;
    }
}
