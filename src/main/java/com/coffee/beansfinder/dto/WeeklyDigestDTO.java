package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyDigestDTO {
    private Long id;
    private LocalDate weekStart;
    private LocalDate weekEnd;
    private Integer version;
    private String narrative;
    private Map<String, Object> metrics;
    private List<NewsArticleDTO> newsArticles;
    private LocalDateTime generatedAt;
    private String status;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewsArticleDTO {
        private Long id;
        private String source;
        private String title;
        private String link;
        private String summary;
        private List<String> themes;
        private String sentiment;
    }
}
