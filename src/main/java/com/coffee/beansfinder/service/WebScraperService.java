package com.coffee.beansfinder.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
     * Extract product sitemap URLs from a sitemap index
     * Handles cases where main sitemap.xml contains links to product-specific sitemaps
     *
     * @param sitemapUrl Main sitemap URL (e.g., https://example.com/sitemap.xml)
     * @return List of product sitemap URLs (e.g., sitemap_products_1.xml, sitemap_products_2.xml)
     */
    public List<String> extractProductSitemapUrls(String sitemapUrl) {
        List<String> productSitemapUrls = new ArrayList<>();

        log.info("Checking if {} is a sitemap index", sitemapUrl);

        try {
            Optional<Document> sitemapDoc = fetchPage(sitemapUrl);

            if (sitemapDoc.isEmpty()) {
                log.warn("Failed to fetch sitemap index: {}", sitemapUrl);
                return productSitemapUrls;
            }

            // Check if this is a sitemap index (contains <sitemapindex> and <sitemap> tags)
            Elements sitemapElements = sitemapDoc.get().select("sitemapindex > sitemap > loc");

            if (sitemapElements.isEmpty()) {
                log.info("{} is not a sitemap index (no <sitemapindex> found)", sitemapUrl);
                return productSitemapUrls;
            }

            log.info("Found sitemap index with {} sub-sitemaps", sitemapElements.size());

            // Extract product sitemap URLs
            for (Element locElement : sitemapElements) {
                String subSitemapUrl = locElement.text();

                if (isProductSitemap(subSitemapUrl)) {
                    productSitemapUrls.add(subSitemapUrl);
                    log.info("Found product sitemap: {}", subSitemapUrl);
                }
            }

            log.info("Extracted {} product sitemap URLs from index", productSitemapUrls.size());

        } catch (Exception e) {
            log.error("Error parsing sitemap index {}: {}", sitemapUrl, e.getMessage(), e);
        }

        return productSitemapUrls;
    }

    /**
     * Check if a sitemap URL is a product sitemap
     * Matches common patterns: sitemap_products, product-sitemap, products.xml
     */
    private boolean isProductSitemap(String sitemapUrl) {
        String lowerUrl = sitemapUrl.toLowerCase();
        return lowerUrl.contains("sitemap_products") ||
               lowerUrl.contains("product-sitemap") ||
               lowerUrl.contains("products.xml") ||
               lowerUrl.contains("product_sitemap");
    }

    /**
     * Extract product URLs from sitemap.xml
     * Automatically handles sitemap indexes by first checking for product-specific sitemaps
     * Filters to only coffee/coffee bean products
     */
    public List<String> extractProductUrlsFromSitemap(String sitemapUrl) {
        List<String> productUrls = new ArrayList<>();

        log.info("Fetching sitemap from: {}", sitemapUrl);

        try {
            // First, check if this is a sitemap index with product sitemaps
            List<String> productSitemaps = extractProductSitemapUrls(sitemapUrl);

            List<String> sitemapsToProcess = new ArrayList<>();
            if (!productSitemaps.isEmpty()) {
                log.info("Found {} product sitemaps, will process all of them", productSitemaps.size());
                sitemapsToProcess.addAll(productSitemaps);
            } else {
                log.info("No product sitemaps found in index, treating {} as direct product sitemap", sitemapUrl);
                sitemapsToProcess.add(sitemapUrl);
            }

            // Process each sitemap (could be multiple for large catalogs)
            for (String sitemap : sitemapsToProcess) {
                log.info("Processing sitemap: {}", sitemap);

                Optional<Document> sitemapDoc = fetchPage(sitemap);

                if (sitemapDoc.isEmpty()) {
                    log.error("Failed to fetch sitemap: {}", sitemap);
                    continue;
                }

                // Parse XML sitemap - URLs are in <loc> tags within <url> elements
                Elements urlElements = sitemapDoc.get().select("url > loc");

                int totalUrls = 0;
                int coffeeUrls = 0;

                for (Element urlElement : urlElements) {
                    String url = urlElement.text();
                    totalUrls++;

                    if (!url.isEmpty() && isCoffeeProduct(url)) {
                        productUrls.add(url);
                        coffeeUrls++;
                    }
                }

                log.info("Extracted {} coffee product URLs from {} total URLs in {}",
                         coffeeUrls, totalUrls, sitemap);
            }

            log.info("Total: {} coffee product URLs extracted from {} sitemap(s)",
                     productUrls.size(), sitemapsToProcess.size());

        } catch (Exception e) {
            log.error("Error parsing sitemap {}: {}", sitemapUrl, e.getMessage(), e);
        }

        return productUrls;
    }

    /**
     * Check if URL is likely a coffee bean product
     * Since we're getting URLs from product sitemaps, we use an exclusion-based approach
     * rather than inclusion - assume it's a product unless it matches known non-coffee patterns
     */
    private boolean isCoffeeProduct(String url) {
        String lowerUrl = url.toLowerCase();

        // First check: must be in a product URL path
        boolean isInProductPath = lowerUrl.contains("/products/") ||
                                  lowerUrl.contains("/product/") ||
                                  lowerUrl.contains("/coffees/") ||
                                  lowerUrl.contains("/coffee/") ||
                                  lowerUrl.contains("/shop/");

        if (!isInProductPath) {
            return false;
        }

        // Exclude known non-coffee product patterns (equipment, accessories, etc.)
        if (lowerUrl.contains("/bundles/") ||
            lowerUrl.contains("/bundle/") ||
            lowerUrl.contains("/accessories/") ||
            lowerUrl.contains("/accessory/") ||
            lowerUrl.contains("/brewing-equipment/") ||
            lowerUrl.contains("/equipment/") ||
            lowerUrl.contains("/coffee-machines/") ||
            lowerUrl.contains("/machines/") ||
            lowerUrl.contains("/coffee-grinders/") ||
            lowerUrl.contains("/grinders/") ||
            lowerUrl.contains("/grinder/") ||
            lowerUrl.contains("/coffee-filters/") ||
            lowerUrl.contains("/filters/") ||
            lowerUrl.contains("/filter-papers/") ||
            lowerUrl.contains("/papers/") ||
            lowerUrl.contains("/coffee-pods/") ||
            lowerUrl.contains("/pods/") ||
            lowerUrl.contains("/christmas/") ||
            lowerUrl.contains("/gift-cards/") ||
            lowerUrl.contains("/gift-card/") ||
            lowerUrl.contains("/gift/") ||
            lowerUrl.contains("/merchandise/") ||
            lowerUrl.contains("/merch/") ||
            lowerUrl.contains("/selection-boxes/") ||
            lowerUrl.contains("/powered-by-pact/") ||
            lowerUrl.contains("/secret-sale/") ||
            lowerUrl.contains("/subscription/") ||
            lowerUrl.contains("/subscriptions/")) {
            return false;
        }

        // Exclude specific product keywords that are equipment/accessories (not coffee beans)
        // Common brewing equipment brands and types
        String[] excludeKeywords = {
            "gift-card",      // Gift cards (check before "gift")
            "subscription",   // Subscription products
            "sibarist",       // Filter paper brand
            "origami",        // Dripper brand
            "hario",          // Equipment brand
            "kalita",         // Equipment brand
            "chemex",         // Dripper brand
            "aeropress",      // Brewer
            "v60",            // Dripper type
            "clever",         // Dripper type
            "dripper",        // Equipment
            "carafe",         // Equipment
            "server",         // Equipment
            "kettle",         // Equipment
            "scale",          // Equipment
            "grinder",        // Equipment
            "tamper",         // Equipment
            "portafilter",    // Equipment
            "basket",         // Equipment
            "spoon",          // Equipment (cupping spoons)
            "scoop",          // Equipment
            "jug",            // Equipment
            "mug",            // Merchandise
            "cup",            // Merchandise
            "glass",          // Merchandise
            "bottle",         // Merchandise
            "flask",          // Merchandise
            "canister",       // Storage
            "jar",            // Storage
            "container",      // Storage
            "tote",           // Merchandise
            "bag",            // Merchandise (but might catch coffee bags - need to be careful)
            "t-shirt",        // Merchandise
            "tshirt",         // Merchandise
            "hoodie",         // Merchandise
            "hat",            // Merchandise
            "cap",            // Merchandise (but not capsule)
            "apron",          // Merchandise
            "coaster",        // Merchandise
            "linen",          // Merchandise
            "porcelain",      // Merchandise
            "ceramic",        // Merchandise
            "book",           // Merchandise
            "gift-bundle",    // Gift bundles
            "cleaning",       // Cleaning supplies
            "descaler",       // Cleaning
            "brush",          // Cleaning
            "cloth",          // Cleaning
            "wiper",          // Cleaning
            "office",         // Office subscriptions
            "burr"            // Equipment part
        };

        for (String keyword : excludeKeywords) {
            if (lowerUrl.contains(keyword)) {
                return false;
            }
        }

        // If it passed all exclusion checks, it's likely a coffee bean product
        return true;
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
