package com.coffee.beansfinder.service;

import com.coffee.beansfinder.config.CrawlerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Web scraper service using Jsoup for fetching and parsing HTML
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WebScraperService {

    private final CrawlerProperties properties;

    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * Fetch HTML content from a URL with retry logic
     */
    public String fetchHtmlContent(String url) throws IOException {
        return fetchHtmlContent(url, properties.getRetryAttempts());
    }

    /**
     * Fetch HTML content with specified number of retries
     */
    private String fetchHtmlContent(String url, int retriesLeft) throws IOException {
        try {
            log.debug("Fetching URL: {} (retries left: {})", url, retriesLeft);

            // Add delay to respect rate limits
            if (properties.getDelaySeconds() > 0) {
                TimeUnit.SECONDS.sleep(properties.getDelaySeconds());
            }

            Document doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(30000)
                .followRedirects(true)
                .get();

            return doc.html();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Scraping interrupted", e);
        } catch (IOException e) {
            if (retriesLeft > 0) {
                log.warn("Failed to fetch URL, retrying... Error: {}", e.getMessage());
                return fetchHtmlContent(url, retriesLeft - 1);
            } else {
                log.error("Failed to fetch URL after all retries: {}", url, e);
                throw e;
            }
        }
    }

    /**
     * Extract main content from HTML document
     */
    public String extractMainContent(String html) {
        Document doc = Jsoup.parse(html);

        // Remove script, style, and navigation elements
        doc.select("script, style, nav, header, footer, .nav, .navigation, .menu").remove();

        // Try to find main content area
        var mainContent = doc.select("main, article, .product-description, .product-details, " +
            ".product-info, #product, .coffee-details").first();

        if (mainContent != null) {
            return mainContent.text();
        }

        // Fallback to body text
        return doc.body().text();
    }

    /**
     * Check if robots.txt allows crawling (basic implementation)
     */
    public boolean isAllowedByCrawl(String url) {
        try {
            String baseUrl = extractBaseUrl(url);
            String robotsTxt = Jsoup.connect(baseUrl + "/robots.txt")
                .userAgent(USER_AGENT)
                .timeout(5000)
                .ignoreHttpErrors(true)
                .execute()
                .body();

            // Very basic check - in production, use a proper robots.txt parser
            return !robotsTxt.toLowerCase().contains("disallow: /");

        } catch (IOException e) {
            log.warn("Could not fetch robots.txt for {}, assuming allowed", url);
            return true; // Assume allowed if can't fetch
        }
    }

    private String extractBaseUrl(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            return u.getProtocol() + "://" + u.getHost();
        } catch (Exception e) {
            return url;
        }
    }
}
