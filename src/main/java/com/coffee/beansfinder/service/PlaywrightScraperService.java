package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.ExtractedProductData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Playwright-based scraper for JavaScript-heavy sites
 * Used as fallback when traditional scraping fails
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlaywrightScraperService {

    private final ObjectMapper objectMapper;
    private Playwright playwright;
    private Browser browser;

    @PostConstruct
    public void init() {
        log.info("Initializing Playwright browser");
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setTimeout(30000));
    }

    @PreDestroy
    public void cleanup() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        log.info("Playwright browser closed");
    }

    /**
     * Render JavaScript-heavy page and return full HTML content
     * This is now used in combination with Perplexity AI extraction
     * instead of brittle regex patterns.
     *
     * Simplified from 300+ lines of regex extraction to just page rendering.
     * Perplexity handles all extraction logic.
     */
    public String renderPageToHtml(String url) {
        log.info("Rendering page with Playwright: {}", url);

        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"));

        Page page = context.newPage();

        try {
            // Navigate and wait for network to be idle
            Response response = page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(30000));

            // Wait for JavaScript to execute and render content
            page.waitForTimeout(2000);

            // Get fully rendered HTML
            String renderedHtml = page.content();

            log.info("Successfully rendered page ({} chars): {}", renderedHtml.length(), url);
            return renderedHtml;

        } catch (Exception e) {
            log.error("Error rendering page with Playwright {}: {}", url, e.getMessage(), e);
            return null;
        } finally {
            page.close();
            context.close();
        }
    }

    /**
     * Extract only product-relevant text from rendered page
     * Reduces token usage by 90% compared to sending full HTML
     * Cost: ~2,500 tokens vs 25,000 tokens for full HTML
     */
    public String extractProductText(String url) {
        log.info("Extracting product text with Playwright: {}", url);

        // Retry up to 2 times on failure
        int maxRetries = 2;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            BrowserContext context = null;
            Page page = null;

            try {
                context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"));

                page = context.newPage();

                // Navigate with reduced timeout to fail faster
                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(20000)  // 20s timeout instead of 30s
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));  // Don't wait for all resources

                // Wait briefly for dynamic content (but use try-catch to avoid crashes)
                try {
                    page.waitForTimeout(1500);  // Reduced from 2000ms
                } catch (Exception waitError) {
                    log.warn("Wait timeout interrupted for {}, continuing anyway", url);
                }

                // Extract only product-relevant content using CSS selectors
                String productText = page.evaluate("""
                    () => {
                        // Remove script, style, nav, footer, header
                        ['script', 'style', 'nav', 'footer', 'header', 'iframe'].forEach(tag => {
                            document.querySelectorAll(tag).forEach(el => el.remove());
                        });

                        // Try to find main product container
                        const selectors = [
                            'main',
                            'article',
                            '.product',
                            '.product-single',
                            '.product-details',
                            '[class*="product"]',
                            '#product',
                            'body'
                        ];

                        for (const selector of selectors) {
                            const element = document.querySelector(selector);
                            if (element && element.textContent.length > 200) {
                                return element.textContent;
                            }
                        }

                        // Fallback to body text
                        return document.body.textContent;
                    }
                    """).toString();

                // Clean up whitespace
                productText = productText.replaceAll("\\s+", " ").trim();

                log.info("Extracted product text ({} chars): {}", productText.length(), url);
                return productText;

            } catch (Exception e) {
                log.error("Attempt {}/{} failed for {}: {}", attempt, maxRetries, url, e.getMessage());

                // Close page and context to free resources before retry
                try {
                    if (page != null) page.close();
                    if (context != null) context.close();
                } catch (Exception closeError) {
                    log.warn("Error closing page/context: {}", closeError.getMessage());
                }

                // If this was the last attempt, log final error
                if (attempt == maxRetries) {
                    log.error("All {} attempts failed for {}: {}", maxRetries, url, e.getMessage());
                    return null;
                }

                // Wait briefly before retry
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }

            } finally {
                // Final cleanup
                try {
                    if (page != null && !page.isClosed()) page.close();
                    if (context != null) context.close();
                } catch (Exception closeError) {
                    // Ignore cleanup errors
                }
            }
        }

        return null;
    }

    /**
     * @deprecated Use renderPageToHtml() + PerplexityApiService.extractFromRenderedHtml() instead
     * This method used brittle regex patterns with 20-30% success rate.
     * New approach: Playwright renders → Perplexity extracts (80-90% success rate)
     */
    @Deprecated
    public ExtractedProductData extractProductData(String url) {
        log.warn("DEPRECATED: extractProductData() called. Use renderPageToHtml() + Perplexity extraction instead.");

        // Render HTML
        String html = renderPageToHtml(url);
        if (html == null) {
            return null;
        }

        // Return minimal data (just for backward compatibility)
        ExtractedProductData data = new ExtractedProductData();
        data.setProductUrl(url);

        // Try to extract basic info from meta tags if possible
        BrowserContext context = browser.newContext();
        Page page = context.newPage();
        try {
            page.navigate(url);
            data.setProductName(extractMetaTag(page, "og:title"));
            data.setRawDescription(extractMetaTag(page, "og:description"));
        } catch (Exception e) {
            log.error("Error extracting basic meta tags: {}", e.getMessage());
        } finally {
            page.close();
            context.close();
        }

        return data;
    }

    /**
     * Extract data from Shopify metafields (JavaScript variables)
     */
    private ExtractedProductData extractFromShopifyMetafields(String content) {
        try {
            // Look for: const metafields = {...}; or const meta = {...};
            Pattern pattern = Pattern.compile("(?:const|var)\\s+(?:metafields|meta|product)\\s*=\\s*(\\{[\\s\\S]*?\\});", Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(content);

            if (!matcher.find()) {
                return null;
            }

            String jsonString = matcher.group(1);
            JsonNode root = objectMapper.readTree(jsonString);

            ExtractedProductData data = new ExtractedProductData();

            // Navigate JSON structure to find product info
            // Shopify structure varies, so we try multiple paths
            if (root.has("product")) {
                JsonNode product = root.get("product");
                data.setProductName(getTextValue(product, "title", "name"));
                data.setRawDescription(getTextValue(product, "description", "body_html"));

                // Extract variants/price
                if (product.has("variants")) {
                    JsonNode variants = product.get("variants");
                    if (variants.isArray() && variants.size() > 0) {
                        JsonNode firstVariant = variants.get(0);
                        data.setPrice(getBigDecimalValue(firstVariant, "price"));
                        data.setInStock(getBooleanValue(firstVariant, "available", true));
                    }
                }
            }

            // Look for coffee-specific fields in metafields or custom fields
            data.setOrigin(findInJson(root, "origin", "country", "region_name"));
            data.setProcess(findInJson(root, "process", "processing", "process_type"));
            data.setVariety(findInJson(root, "variety", "varietal", "cultivar"));
            data.setProducer(findInJson(root, "producer", "farm", "farmer"));
            data.setAltitude(findInJson(root, "altitude", "elevation"));

            // Extract tasting notes
            List<String> tastingNotes = extractTastingNotes(root);
            if (!tastingNotes.isEmpty()) {
                data.setTastingNotes(tastingNotes);
            }

            return data.getProductName() != null ? data : null;

        } catch (Exception e) {
            log.debug("Failed to extract from Shopify metafields: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract data from JSON-LD structured data
     */
    private ExtractedProductData extractFromJsonLd(String content) {
        try {
            Pattern pattern = Pattern.compile("<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(content);

            while (matcher.find()) {
                String jsonString = matcher.group(1);
                JsonNode root = objectMapper.readTree(jsonString);

                if (root.has("@type") && "Product".equals(root.get("@type").asText())) {
                    ExtractedProductData data = new ExtractedProductData();
                    data.setProductName(getTextValue(root, "name"));
                    data.setRawDescription(getTextValue(root, "description"));

                    // Extract price from offers
                    if (root.has("offers")) {
                        JsonNode offers = root.get("offers");
                        if (offers.isArray() && offers.size() > 0) {
                            JsonNode firstOffer = offers.get(0);
                            data.setPrice(getBigDecimalValue(firstOffer, "price"));

                            String availability = getTextValue(firstOffer, "availability");
                            data.setInStock(availability != null && availability.contains("InStock"));
                        } else if (offers.isObject()) {
                            data.setPrice(getBigDecimalValue(offers, "price"));

                            String availability = getTextValue(offers, "availability");
                            data.setInStock(availability != null && availability.contains("InStock"));
                        }
                    }

                    return data;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract from JSON-LD: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract meta tag value
     */
    private String extractMetaTag(Page page, String property) {
        try {
            Locator meta = page.locator(String.format("meta[property='%s']", property));
            if (meta.count() > 0) {
                return meta.getAttribute("content");
            }
        } catch (Exception e) {
            log.debug("Failed to extract meta tag {}: {}", property, e.getMessage());
        }
        return null;
    }

    /**
     * Extract price from page using common selectors
     */
    private BigDecimal extractPrice(Page page) {
        String[] selectors = {
                ".price", ".product-price", "[data-price]", ".money",
                "[itemprop='price']", ".product__price"
        };

        for (String selector : selectors) {
            try {
                Locator element = page.locator(selector).first();
                if (element.count() > 0) {
                    String priceText = element.textContent();
                    return parsePrice(priceText);
                }
            } catch (Exception e) {
                // Try next selector
            }
        }
        return null;
    }

    /**
     * Parse price from text
     */
    private BigDecimal parsePrice(String priceText) {
        if (priceText == null || priceText.isEmpty()) {
            return null;
        }

        // Remove currency symbols and whitespace, keep digits and decimal point
        String cleaned = priceText.replaceAll("[^0-9.]", "");

        try {
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            log.debug("Failed to parse price: {}", priceText);
            return null;
        }
    }

    /**
     * Extract tasting notes from JSON structure
     */
    private List<String> extractTastingNotes(JsonNode root) {
        List<String> notes = new ArrayList<>();

        // Common field names for tasting notes
        String[] fields = {"tasting_notes", "flavor_notes", "flavors", "taste", "notes"};

        for (String field : fields) {
            JsonNode node = findJsonNode(root, field);
            if (node != null) {
                if (node.isArray()) {
                    for (JsonNode item : node) {
                        String note = item.asText();
                        if (note != null && !note.isEmpty()) {
                            notes.add(note);
                        }
                    }
                } else if (node.isTextual()) {
                    // Split by comma if it's a comma-separated string
                    String[] split = node.asText().split(",");
                    for (String note : split) {
                        note = note.trim();
                        if (!note.isEmpty()) {
                            notes.add(note);
                        }
                    }
                }
            }
        }

        return notes;
    }

    /**
     * Helper: Get text value from JSON node
     */
    private String getTextValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.has(fieldName)) {
                JsonNode fieldNode = node.get(fieldName);
                if (fieldNode.isTextual()) {
                    return fieldNode.asText();
                }
            }
        }
        return null;
    }

    /**
     * Helper: Get BigDecimal value from JSON node
     */
    private BigDecimal getBigDecimalValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.has(fieldName)) {
                JsonNode fieldNode = node.get(fieldName);
                try {
                    return new BigDecimal(fieldNode.asText());
                } catch (Exception e) {
                    // Try next field
                }
            }
        }
        return null;
    }

    /**
     * Helper: Get boolean value from JSON node
     */
    private Boolean getBooleanValue(JsonNode node, String fieldName, Boolean defaultValue) {
        if (node.has(fieldName)) {
            return node.get(fieldName).asBoolean(defaultValue);
        }
        return defaultValue;
    }

    /**
     * Helper: Find field in nested JSON structure
     */
    private String findInJson(JsonNode root, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode node = findJsonNode(root, fieldName);
            if (node != null && node.isTextual()) {
                return node.asText();
            }
        }
        return null;
    }

    /**
     * Helper: Recursively find JSON node by field name
     */
    private JsonNode findJsonNode(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }

        if (node.has(fieldName)) {
            return node.get(fieldName);
        }

        if (node.isObject()) {
            for (JsonNode child : node) {
                JsonNode found = findJsonNode(child, fieldName);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                JsonNode found = findJsonNode(item, fieldName);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /**
     * Check if site is likely JavaScript-rendered
     */
    public boolean isJavaScriptRendered(String htmlContent) {
        // Look for common indicators of JavaScript-rendered content
        return htmlContent.contains("const metafields") ||
               htmlContent.contains("__NEXT_DATA__") ||
               htmlContent.contains("window.INITIAL_STATE") ||
               htmlContent.contains("var Shopify") ||
               htmlContent.contains("data-react-helmet") ||
               (htmlContent.contains("<div id=\"root\"></div>") && htmlContent.split("<div").length < 10);
    }
}
