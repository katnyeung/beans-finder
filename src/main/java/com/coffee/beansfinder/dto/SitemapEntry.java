package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents a single URL entry from a sitemap XML with its metadata.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SitemapEntry {
    private String url;
    private LocalDateTime lastModified;
    private String title;  // From <image:title> if available

    public SitemapEntry(String url) {
        this.url = url;
    }

    public SitemapEntry(String url, LocalDateTime lastModified) {
        this.url = url;
        this.lastModified = lastModified;
    }
}
