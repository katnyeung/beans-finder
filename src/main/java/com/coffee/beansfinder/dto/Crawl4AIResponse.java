package com.coffee.beansfinder.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * Response DTO for Crawl4AI microservice.
 * Maps to the CrawlResponse from the Python FastAPI service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Crawl4AIResponse {

    private String url;

    private String method;

    private boolean success;

    private String markdown;

    private String html;

    @JsonProperty("cleaned_html")
    private String cleanedHtml;

    private List<String> links;

    private String title;

    private String error;

    @JsonProperty("duration_ms")
    private int durationMs;

    @JsonProperty("content_length")
    private int contentLength;
}
