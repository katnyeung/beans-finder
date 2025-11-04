package com.coffee.beansfinder.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

/**
 * Service for scraping coffee product pages using Jsoup
 */
@Service
@Slf4j
public class WebScraperService {

    @Value("${crawler.user.agent}")
    private String userAgent;

    @Value("${crawler.delay.seconds:2}")
    private int delaySeconds;

    @Value("${crawler.retry.attempts:3}")
    private int retryAttempts;

    private long lastRequestTime = 0;

    /**
     * Fetch and parse HTML content from a URL
     */
    public Optional<Document> fetchPage(String url) {
        if (url == null || url.isEmpty()) {
            log.warn("Empty URL provided");
            return Optional.empty();
        }

        // Rate limiting - respect delay between requests
        enforceDelay();

        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                log.info("Fetching page (attempt {}/{}): {}", attempt, retryAttempts, url);

                Document doc = Jsoup.connect(url)
                        .userAgent(userAgent)
                        .timeout(30000)
                        .followRedirects(true)
                        .get();

                log.info("Successfully fetched page: {}", url);
                return Optional.of(doc);

            } catch (IOException e) {
                log.error("Failed to fetch page (attempt {}/{}): {} - {}",
                        attempt, retryAttempts, url, e.getMessage());

                if (attempt == retryAttempts) {
                    log.error("All retry attempts exhausted for URL: {}", url);
                    return Optional.empty();
                }

                // Exponential backoff
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Extract raw text content from document
     */
    public String extractTextContent(Document document) {
        if (document == null) {
            return "";
        }

        // Remove script and style elements
        document.select("script, style, nav, footer, header").remove();

        // Get main content (prioritize common content containers)
        Elements contentElements = document.select(
                "main, article, .product, .product-details, .product-info, " +
                ".description, .product-description, [class*='content']");

        if (!contentElements.isEmpty()) {
            return contentElements.text();
        }

        // Fallback to body content
        Element body = document.body();
        return body != null ? body.text() : "";
    }

    /**
     * Extract product metadata from document
     */
    public ProductPageMetadata extractMetadata(Document document) {
        ProductPageMetadata metadata = new ProductPageMetadata();

        if (document == null) {
            return metadata;
        }

        // Try to extract product name from various sources
        metadata.title = extractTitle(document);
        metadata.description = extractDescription(document);
        metadata.price = extractPrice(document);
        metadata.availability = extractAvailability(document);

        // Extract structured data (JSON-LD, microdata)
        metadata.structuredData = extractStructuredData(document);

        return metadata;
    }

    /**
     * Extract title from various possible locations
     */
    private String extractTitle(Document doc) {
        // Try product-specific selectors first
        Elements titleElements = doc.select(
                "h1.product-title, h1.product-name, .product h1, " +
                "[itemprop='name'], meta[property='og:title']");

        if (!titleElements.isEmpty()) {
            Element first = titleElements.first();
            return first.hasAttr("content") ? first.attr("content") : first.text();
        }

        // Fallback to page title
        return doc.title();
    }

    /**
     * Extract description
     */
    private String extractDescription(Document doc) {
        Elements descElements = doc.select(
                ".product-description, .description, [itemprop='description'], " +
                "meta[property='og:description'], meta[name='description']");

        if (!descElements.isEmpty()) {
            Element first = descElements.first();
            return first.hasAttr("content") ? first.attr("content") : first.text();
        }

        return "";
    }

    /**
     * Extract price
     */
    private String extractPrice(Document doc) {
        Elements priceElements = doc.select(
                ".price, .product-price, [itemprop='price'], " +
                "[class*='price'], [id*='price']");

        if (!priceElements.isEmpty()) {
            return priceElements.first().text();
        }

        return null;
    }

    /**
     * Extract availability/stock status
     */
    private String extractAvailability(Document doc) {
        Elements stockElements = doc.select(
                ".stock, .availability, [itemprop='availability'], " +
                ".in-stock, .out-of-stock");

        if (!stockElements.isEmpty()) {
            return stockElements.first().text();
        }

        return null;
    }

    /**
     * Extract structured data (JSON-LD)
     */
    private String extractStructuredData(Document doc) {
        Elements scriptElements = doc.select("script[type='application/ld+json']");

        if (!scriptElements.isEmpty()) {
            return scriptElements.first().html();
        }

        return null;
    }

    /**
     * Enforce rate limiting between requests
     */
    private void enforceDelay() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastRequest = currentTime - lastRequestTime;
        long requiredDelay = delaySeconds * 1000L;

        if (timeSinceLastRequest < requiredDelay) {
            long sleepTime = requiredDelay - timeSinceLastRequest;
            try {
                log.debug("Rate limiting: sleeping for {}ms", sleepTime);
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        lastRequestTime = System.currentTimeMillis();
    }

    /**
     * Check if robots.txt allows crawling
     */
    public boolean isAllowedByRobotsTxt(String url, String robotsTxtUrl) {
        try {
            Optional<Document> robotsTxt = fetchPage(robotsTxtUrl);
            if (robotsTxt.isPresent()) {
                String content = robotsTxt.get().text();
                // Simple robots.txt parsing (can be enhanced)
                return !content.contains("Disallow: /");
            }
        } catch (Exception e) {
            log.warn("Could not check robots.txt: {}", e.getMessage());
        }

        // Default to allowed if can't verify
        return true;
    }

    /**
     * Metadata extracted from a product page
     */
    public static class ProductPageMetadata {
        public String title;
        public String description;
        public String price;
        public String availability;
        public String structuredData;
    }
}
