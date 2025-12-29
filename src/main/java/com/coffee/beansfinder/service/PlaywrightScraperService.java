package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.Crawl4AIResponse;
import com.coffee.beansfinder.dto.ExtractedProductData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Playwright-based scraper for JavaScript-heavy sites
 * Now integrated with Crawl4AI Python microservice for enhanced extraction.
 * Crawl4AI is tried first (if enabled), with Playwright as fallback.
 */
@Service
@Slf4j
public class PlaywrightScraperService {

    private final ObjectMapper objectMapper;
    private final CrawlClientService crawlClientService;
    private Playwright playwright;
    private Browser browser;
    private final Random random = new Random();
    private int consecutiveFailures = 0;
    private static final int MAX_CONSECUTIVE_FAILURES_BEFORE_RESTART = 3;

    public PlaywrightScraperService(ObjectMapper objectMapper, CrawlClientService crawlClientService) {
        this.objectMapper = objectMapper;
        this.crawlClientService = crawlClientService;
    }

    // Common user agents for rotation (Chrome on Windows/Mac)
    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15"
    };

    // Common viewport sizes for rotation
    private static final int[][] VIEWPORTS = {
        {1920, 1080},
        {1366, 768},
        {1536, 864},
        {1440, 900},
        {1280, 720},
        {1600, 900},
        {2560, 1440}
    };

    @PostConstruct
    public void init() {
        log.info("Initializing Playwright browser");
        initBrowser();
    }

    /**
     * Initialize or reinitialize the browser
     */
    private synchronized void initBrowser() {
        try {
            // Close existing browser if any
            if (browser != null) {
                try {
                    browser.close();
                } catch (Exception e) {
                    log.warn("Error closing existing browser: {}", e.getMessage());
                }
            }
            if (playwright == null) {
                playwright = Playwright.create();
            }

            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setTimeout(30000)
                    .setArgs(List.of(
                        "--disable-blink-features=AutomationControlled",
                        "--disable-dev-shm-usage",
                        "--no-sandbox",
                        "--disable-setuid-sandbox",
                        "--disable-web-security",
                        "--disable-features=IsolateOrigins,site-per-process"
                    )));
            log.info("Playwright browser initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize Playwright browser: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize browser", e);
        }
    }

    /**
     * Restart browser if it seems stuck (called after consecutive failures)
     */
    public synchronized void restartBrowser() {
        log.warn("Restarting Playwright browser due to failures");
        initBrowser();
    }

    /**
     * Get a random user agent string
     */
    private String getRandomUserAgent() {
        return USER_AGENTS[random.nextInt(USER_AGENTS.length)];
    }

    /**
     * Get a random viewport size
     */
    private int[] getRandomViewport() {
        return VIEWPORTS[random.nextInt(VIEWPORTS.length)];
    }

    /**
     * Create a browser context with randomized fingerprint to avoid detection
     */
    private BrowserContext createStealthContext() {
        String userAgent = getRandomUserAgent();
        int[] viewport = getRandomViewport();

        log.debug("Using fingerprint: UA={}, viewport={}x{}",
                userAgent.substring(0, 50) + "...", viewport[0], viewport[1]);

        return browser.newContext(new Browser.NewContextOptions()
                .setUserAgent(userAgent)
                .setViewportSize(viewport[0], viewport[1])
                .setLocale("en-GB")
                .setTimezoneId("Europe/London")
                .setDeviceScaleFactor(1.0)
                .setHasTouch(false)
                .setJavaScriptEnabled(true)
                .setIgnoreHTTPSErrors(true));
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

        BrowserContext context = createStealthContext();
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
     * Extract only product-relevant text from rendered page.
     * Now integrated with Crawl4AI Python microservice:
     * - First tries Crawl4AI if enabled (produces LLM-friendly markdown)
     * - Falls back to Playwright if Crawl4AI fails or returns poor quality
     *
     * Reduces token usage by 90% compared to sending full HTML.
     * Cost: ~2,500 tokens vs 25,000 tokens for full HTML
     */
    public String extractProductText(String url) {
        // Try Crawl4AI first if enabled
        if (crawlClientService.isEnabled()) {
            try {
                log.info("[Crawl4AI] Attempting extraction for: {}", url);
                Crawl4AIResponse response = crawlClientService.crawlSmart(url);

                if (response != null && response.isSuccess()) {
                    String content = response.getMarkdown();
                    if (content != null && content.length() > 300) {
                        log.info("[Crawl4AI] Success ({} chars): {}", content.length(), url);
                        return content;
                    }
                    log.info("[Crawl4AI] Content too short ({} chars), falling back to Playwright: {}",
                            content != null ? content.length() : 0, url);
                } else {
                    log.info("[Crawl4AI] Failed ({}), falling back to Playwright: {}",
                            response != null ? response.getError() : "null response", url);
                }
            } catch (Exception e) {
                log.warn("[Crawl4AI] Exception, falling back to Playwright: {} - {}", url, e.getMessage());
            }
        }

        // Fallback to Playwright
        log.info("Extracting product text with Playwright: {}", url);

        // Retry up to 2 times on failure
        int maxRetries = 2;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            BrowserContext context = null;
            Page page = null;

            try {
                log.info("Attempt {}/{} - Creating stealth browser context", attempt, maxRetries);
                context = createStealthContext();
                page = context.newPage();

                // Add random delay before navigation (1-3 seconds) to simulate human behavior
                int delay = 1000 + random.nextInt(2000);
                log.info("Waiting {}ms before navigation to {}", delay, url);
                Thread.sleep(delay);

                // Navigate with reduced timeout to fail faster
                log.info("Navigating to {} (timeout: 25s)", url);
                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(25000)  // 25s timeout
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));  // Don't wait for all resources
                log.info("Navigation complete for {}", url);

                // Wait for dynamic content and try to expand accordions/tabs
                try {
                    log.info("Waiting 2s for dynamic content...");
                    page.waitForTimeout(2000);

                    // Try to expand any collapsed accordion sections or tabs that might contain tasting notes
                    log.info("Attempting to expand collapsed content sections...");
                    page.evaluate("""
                        () => {
                            // Click on accordion headers, tabs, or "show more" buttons
                            const expandSelectors = [
                                '.accordion__header:not(.is-open)',
                                '.accordion-trigger:not([aria-expanded="true"])',
                                '.tab-link:not(.active)',
                                '.collapsible-trigger:not(.is-open)',
                                '[data-toggle="collapse"]:not(.collapsed)',
                                '.product__description-toggle',
                                '.read-more-btn',
                                '.show-more',
                                'button:contains("Read more")',
                                'button:contains("Show more")',
                                'details:not([open]) summary'
                            ];

                            expandSelectors.forEach(selector => {
                                try {
                                    document.querySelectorAll(selector).forEach(el => {
                                        try { el.click(); } catch(e) {}
                                    });
                                } catch(e) {}
                            });

                            // Open any <details> elements
                            document.querySelectorAll('details:not([open])').forEach(d => {
                                try { d.setAttribute('open', ''); } catch(e) {}
                            });
                        }
                        """);

                    // Wait a bit more for expanded content to render
                    page.waitForTimeout(500);
                } catch (Exception waitError) {
                    log.warn("Wait/expand timeout interrupted for {}, continuing anyway", url);
                }

                // Extract only product-relevant content using CSS selectors
                log.info("Extracting text content via JavaScript evaluation...");
                String productText = page.evaluate("""
                    () => {
                        // Quick removal of non-content elements (no iteration over all elements)
                        ['script', 'style', 'nav', 'footer', 'header', 'iframe', 'noscript'].forEach(tag => {
                            document.querySelectorAll(tag).forEach(el => {
                                try { el.remove(); } catch (e) {}
                            });
                        });

                        // Collect text from multiple product-related areas (not just the first match)
                        let allText = [];

                        // FIRST: Extract meta description (often contains tasting notes!)
                        const metaDesc = document.querySelector('meta[name="description"]');
                        if (metaDesc && metaDesc.content) {
                            allText.push('META_DESCRIPTION: ' + metaDesc.content.trim());
                        }
                        const ogDesc = document.querySelector('meta[property="og:description"]');
                        if (ogDesc && ogDesc.content && ogDesc.content !== (metaDesc?.content || '')) {
                            allText.push('OG_DESCRIPTION: ' + ogDesc.content.trim());
                        }

                        // Product description selectors (collect from all matching elements)
                        const descriptionSelectors = [
                            '.product-single__description',
                            '.product__description',
                            '.product-description',
                            '.rte',  // Rich text editor content (common in Shopify)
                            '.product__content-text',
                            '.product-meta',
                            '[data-product-description]',
                            '.accordion__content',
                            '.tab-content',
                            '.product__info-wrapper',
                            '.product-single__content-text',
                            // Additional selectors for various Shopify themes
                            '.product__info',
                            '.product-info__description',
                            '.product-details__description',
                            '.product-block',
                            '.product__details',
                            '[class*="product-info"]',
                            '[class*="product-detail"]',
                            '[class*="product-description"]',
                            // Flavor/tasting notes specific
                            '[class*="flavor"]',
                            '[class*="tasting"]',
                            '[class*="taste"]',
                            '[class*="notes"]',
                            '[class*="profile"]'
                        ];

                        descriptionSelectors.forEach(selector => {
                            try {
                                document.querySelectorAll(selector).forEach(el => {
                                    const text = el.innerText || el.textContent;
                                    // Allow shorter text (10+ chars) for flavor-related selectors
                                    const minLength = selector.includes('flavor') || selector.includes('tasting') ||
                                                      selector.includes('taste') || selector.includes('notes') ||
                                                      selector.includes('profile') ? 10 : 50;
                                    if (text && text.trim().length > minLength) {
                                        allText.push(text.trim());
                                    }
                                });
                            } catch(e) {}
                        });

                        // If we found description content, combine it
                        if (allText.length > 0) {
                            return allText.join('\\n\\n');
                        }

                        // Fallback: Try to find main product container
                        const containerSelectors = [
                            '.product-single__content',
                            '.product__content',
                            '.product-details',
                            '.product-single',
                            '.product-info',
                            'main .product',
                            'article.product',
                            '.product',
                            'main',
                            'article',
                            '#MainContent',
                            '#main-content',
                            '[role="main"]',
                            'body'
                        ];

                        for (const selector of containerSelectors) {
                            try {
                                const element = document.querySelector(selector);
                                if (element) {
                                    const text = element.innerText || element.textContent;
                                    if (text && text.length > 200) {
                                        return text;
                                    }
                                }
                            } catch (e) {}
                        }

                        // Final fallback to body text
                        return document.body.innerText || document.body.textContent || '';
                    }
                    """).toString();

                // Clean up whitespace
                productText = productText.replaceAll("\\s+", " ").trim();

                log.info("Extracted product text ({} chars): {}", productText.length(), url);
                consecutiveFailures = 0; // Reset on success
                return productText;

            } catch (Exception e) {
                log.error("Attempt {}/{} failed for {}: {}", attempt, maxRetries, url, e.getMessage());
                consecutiveFailures++;

                // Close page and context to free resources before retry
                try {
                    if (page != null) page.close();
                    if (context != null) context.close();
                } catch (Exception closeError) {
                    log.warn("Error closing page/context: {}", closeError.getMessage());
                }

                // Restart browser if too many consecutive failures
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES_BEFORE_RESTART) {
                    log.warn("Too many consecutive failures ({}), restarting browser", consecutiveFailures);
                    restartBrowser();
                    consecutiveFailures = 0;
                }

                // If this was the last attempt, log final error
                if (attempt == maxRetries) {
                    log.error("All {} attempts failed for {}: {}", maxRetries, url, e.getMessage());
                    return null;
                }

                // Wait briefly before retry (with some randomization)
                try {
                    Thread.sleep(2000 + random.nextInt(2000));
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
     * Try to fetch Shopify product JSON (contains origin, process, tasting notes in body_html).
     * Shopify stores expose product data at /products/{handle}.json
     *
     * @param url Product page URL (e.g., https://example.com/products/coffee-name)
     * @return Product JSON body_html content, or null if not available
     */
    public String fetchShopifyProductJson(String url) {
        // Convert product URL to JSON endpoint
        // /products/coffee-name -> /products/coffee-name.json
        // /collections/x/products/coffee-name -> /products/coffee-name.json
        String jsonUrl = url;

        // Handle collection URLs: extract just the product part
        if (url.contains("/collections/") && url.contains("/products/")) {
            int productsIndex = url.lastIndexOf("/products/");
            jsonUrl = url.substring(0, url.indexOf("/collections/")) + url.substring(productsIndex);
        }

        // Remove query parameters and add .json
        if (jsonUrl.contains("?")) {
            jsonUrl = jsonUrl.substring(0, jsonUrl.indexOf("?"));
        }
        if (!jsonUrl.endsWith(".json")) {
            jsonUrl = jsonUrl + ".json";
        }

        log.info("Fetching Shopify product JSON: {}", jsonUrl);

        BrowserContext context = null;
        Page page = null;

        try {
            context = createStealthContext();
            page = context.newPage();

            page.navigate(jsonUrl, new Page.NavigateOptions()
                    .setTimeout(15000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            String content = page.content();

            // Extract JSON from page (it's wrapped in HTML by browser)
            if (content.contains("\"body_html\"")) {
                // Parse the JSON to extract body_html
                int bodyHtmlStart = content.indexOf("\"body_html\":\"");
                if (bodyHtmlStart != -1) {
                    bodyHtmlStart += 13; // Skip past "body_html":"
                    int bodyHtmlEnd = content.indexOf("\",\"vendor\"", bodyHtmlStart);
                    if (bodyHtmlEnd == -1) {
                        bodyHtmlEnd = content.indexOf("\",\"", bodyHtmlStart);
                    }
                    if (bodyHtmlEnd != -1) {
                        String bodyHtml = content.substring(bodyHtmlStart, bodyHtmlEnd);
                        // Unescape JSON string
                        bodyHtml = bodyHtml.replace("\\u003c", "<")
                                          .replace("\\u003e", ">")
                                          .replace("\\u0026", "&")
                                          .replace("\\/", "/")
                                          .replace("\\n", "\n")
                                          .replace("\\\"", "\"");
                        // Strip HTML tags
                        bodyHtml = bodyHtml.replaceAll("<[^>]+>", " ")
                                          .replaceAll("\\s+", " ")
                                          .trim();
                        log.info("Extracted Shopify body_html ({} chars): {}", bodyHtml.length(), url);
                        return bodyHtml;
                    }
                }
            }

            log.warn("No body_html found in Shopify JSON for: {}", url);
            return null;

        } catch (Exception e) {
            log.debug("Failed to fetch Shopify product JSON for {}: {}", url, e.getMessage());
            return null;
        } finally {
            try {
                if (page != null && !page.isClosed()) page.close();
                if (context != null) context.close();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Extract WIDER product text content for retry scenarios.
     * Used when the targeted extraction (extractProductText) fails to find tasting notes.
     * This method extracts the FULL page body text instead of targeted selectors,
     * which may capture tasting notes in unexpected locations.
     *
     * @param url Product page URL
     * @return Extracted text from full page body
     */
    public String extractProductTextWide(String url) {
        log.info("Extracting WIDE product text with Playwright (fallback): {}", url);

        BrowserContext context = null;
        Page page = null;

        try {
            context = createStealthContext();
            page = context.newPage();

            // Add random delay
            int delay = 1000 + random.nextInt(2000);
            Thread.sleep(delay);

            // Navigate
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(25000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // Wait for dynamic content
            page.waitForTimeout(3000);

            // Try to expand collapsed sections
            page.evaluate("""
                () => {
                    const expandSelectors = [
                        '.accordion__header:not(.is-open)',
                        '.accordion-trigger:not([aria-expanded="true"])',
                        '.tab-link:not(.active)',
                        '.collapsible-trigger:not(.is-open)',
                        'details:not([open]) summary'
                    ];
                    expandSelectors.forEach(selector => {
                        try {
                            document.querySelectorAll(selector).forEach(el => {
                                try { el.click(); } catch(e) {}
                            });
                        } catch(e) {}
                    });
                    document.querySelectorAll('details:not([open])').forEach(d => {
                        try { d.setAttribute('open', ''); } catch(e) {}
                    });
                }
                """);

            page.waitForTimeout(500);

            // Extract EVERYTHING - full body text, all meta tags, JSON-LD structured data
            String wideText = page.evaluate("""
                () => {
                    let allText = [];

                    // 1. ALL meta tags (description, og:description, twitter:description, keywords)
                    const metaTags = ['description', 'keywords'];
                    metaTags.forEach(name => {
                        const meta = document.querySelector('meta[name="' + name + '"]');
                        if (meta && meta.content) {
                            allText.push('META_' + name.toUpperCase() + ': ' + meta.content.trim());
                        }
                    });
                    const ogDesc = document.querySelector('meta[property="og:description"]');
                    if (ogDesc && ogDesc.content) {
                        allText.push('OG_DESCRIPTION: ' + ogDesc.content.trim());
                    }

                    // 2. JSON-LD structured data (often contains product info)
                    document.querySelectorAll('script[type="application/ld+json"]').forEach(script => {
                        try {
                            const json = JSON.parse(script.textContent);
                            if (json.description) {
                                allText.push('JSON_LD_DESCRIPTION: ' + json.description);
                            }
                            if (json.name) {
                                allText.push('JSON_LD_NAME: ' + json.name);
                            }
                        } catch(e) {}
                    });

                    // 3. Product JSON embedded in page (Shopify pattern)
                    const productJsonMatch = document.body.innerHTML.match(/var\\s+product\\s*=\\s*(\\{[^;]+\\});/);
                    if (productJsonMatch) {
                        try {
                            const productJson = JSON.parse(productJsonMatch[1]);
                            if (productJson.description) {
                                allText.push('SHOPIFY_PRODUCT_DESCRIPTION: ' + productJson.description.replace(/<[^>]*>/g, ' '));
                            }
                        } catch(e) {}
                    }

                    // 4. Remove scripts/styles but keep all body text
                    ['script', 'style', 'iframe', 'noscript'].forEach(tag => {
                        document.querySelectorAll(tag).forEach(el => {
                            try { el.remove(); } catch (e) {}
                        });
                    });

                    // 5. Get FULL body text (not just product sections)
                    const bodyText = document.body.innerText || document.body.textContent || '';
                    allText.push('BODY_TEXT: ' + bodyText);

                    return allText.join('\\n\\n');
                }
                """).toString();

            // Clean up whitespace
            wideText = wideText.replaceAll("\\s+", " ").trim();

            // Limit to 60KB to avoid token limits
            if (wideText.length() > 60000) {
                wideText = wideText.substring(0, 60000);
            }

            log.info("Extracted WIDE product text ({} chars): {}", wideText.length(), url);
            return wideText;

        } catch (Exception e) {
            log.error("Wide extraction failed for {}: {}", url, e.getMessage());
            return null;
        } finally {
            try {
                if (page != null && !page.isClosed()) page.close();
                if (context != null) context.close();
            } catch (Exception closeError) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Extract homepage text for discount/promotion extraction.
     * Unlike extractProductText(), this method KEEPS header notification bars and announcement sections.
     *
     * @param url Homepage URL
     * @return Extracted text including promotional content
     */
    public String extractHomepageForDiscounts(String url) {
        log.info("Extracting homepage for discounts: {}", url);

        int maxRetries = 2;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            BrowserContext context = null;
            Page page = null;

            try {
                context = createStealthContext();
                page = context.newPage();

                // Random delay to simulate human behavior
                Thread.sleep(1000 + random.nextInt(2000));

                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(25000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                // Wait for dynamic content
                page.waitForTimeout(2000);

                // Extract promotional text - keep headers, notification bars, banners
                String promoText = page.evaluate("""
                    () => {
                        // Remove only scripts and styles, keep headers for notification bars
                        ['script', 'style', 'iframe', 'noscript'].forEach(tag => {
                            document.querySelectorAll(tag).forEach(el => {
                                try { el.remove(); } catch (e) {}
                            });
                        });

                        let allText = [];

                        // Promotional selectors - notification bars, announcements, banners
                        const promoSelectors = [
                            // Notification bars (common patterns)
                            '.header-notification',
                            '.header-notification__content',
                            '.announcement-bar',
                            '.announcement-bar__message',
                            '.announcement',
                            '.top-bar',
                            '.promo-bar',
                            '.site-header__announcement',
                            '[class*="announcement"]',
                            '[class*="notification"]',
                            '[class*="promo-bar"]',
                            '[class*="top-banner"]',
                            // Utility bar / info bar (common Shopify patterns)
                            '.utility-bar',
                            '.info-bar',
                            '.marquee',
                            '[class*="utility-bar"]',
                            '[class*="info-bar"]',
                            '[class*="marquee"]',
                            '[class*="ticker"]',
                            '[class*="topbar"]',
                            '[class*="top_bar"]',
                            // Data attributes for announcements
                            '[data-section-type="announcement"]',
                            '[data-section-type="announcement-bar"]',
                            '[data-announcement]',
                            // Hero/banner sections
                            '.hero',
                            '.hero__content',
                            '.banner',
                            '.banner__content',
                            '[class*="hero"]',
                            '[class*="banner"]',
                            // Popup/modal content (text only)
                            '.popup__content',
                            '.modal__content',
                            '[class*="popup"]',
                            '[class*="modal"]',
                            // Free shipping bars
                            '[class*="shipping"]',
                            '[class*="delivery"]',
                            '[class*="free-shipping"]',
                            // Sale/offer sections
                            '[class*="sale"]',
                            '[class*="offer"]',
                            '[class*="discount"]',
                            '[class*="promo"]',
                            // Subscription offers (common patterns)
                            '[class*="subscribe"]',
                            '[class*="subscription"]',
                            '[class*="save"]',
                            '[class*="savings"]',
                            // Slideshow/carousel content
                            '.slideshow',
                            '.slideshow__slide',
                            '.carousel',
                            '[class*="slideshow"]',
                            '[class*="carousel"]',
                            '[class*="slider"]',
                            // Featured/highlight sections
                            '.featured',
                            '[class*="featured"]',
                            '[class*="highlight"]',
                            // Call-to-action sections
                            '.cta',
                            '[class*="cta"]',
                            // Header area (where sticky promos often appear)
                            'header',
                            '.header',
                            '.site-header'
                        ];

                        promoSelectors.forEach(selector => {
                            try {
                                document.querySelectorAll(selector).forEach(el => {
                                    const text = el.innerText || el.textContent;
                                    if (text && text.trim().length > 5 && text.trim().length < 1000) {
                                        allText.push(text.trim());
                                    }
                                });
                            } catch(e) {}
                        });

                        // Also scan for text containing promo keywords anywhere on page
                        const promoKeywords = ['free shipping', 'free delivery', 'free uk', 'off your', '% off', 'discount', 'save ', 'subscribe', 'code:', 'use code', 'first order'];
                        const allElements = document.querySelectorAll('div, span, p, a, li, section');
                        allElements.forEach(el => {
                            try {
                                const text = (el.innerText || el.textContent || '').trim().toLowerCase();
                                if (text.length > 10 && text.length < 200) {
                                    for (const keyword of promoKeywords) {
                                        if (text.includes(keyword)) {
                                            allText.push((el.innerText || el.textContent).trim());
                                            break;
                                        }
                                    }
                                }
                            } catch(e) {}
                        });

                        // Deduplicate
                        allText = [...new Set(allText)];

                        // Always include top portion of body for context
                        const body = document.body.innerText || document.body.textContent || '';
                        const topPortion = body.substring(0, 3000);

                        // Combine: promo content + top portion
                        if (allText.length > 0) {
                            return allText.join('\\n\\n') + '\\n\\n--- Page Top ---\\n\\n' + topPortion;
                        }

                        // Fallback: return top portion of page (first 8000 chars)
                        return body.substring(0, 8000);
                    }
                    """).toString();

                log.info("Extracted homepage promo text ({} chars): {}", promoText.length(), url);
                return promoText.trim();

            } catch (Exception e) {
                log.error("Attempt {}/{} failed for homepage {}: {}", attempt, maxRetries, url, e.getMessage());
                if (attempt == maxRetries) {
                    return null;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } finally {
                try {
                    if (page != null && !page.isClosed()) page.close();
                    if (context != null) context.close();
                } catch (Exception e) {
                    // Ignore
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
     * Extract article content from news pages (Perfect Daily Grind, Daily Coffee News, etc.)
     * Returns the main article text without navigation, ads, sidebars, etc.
     *
     * @param url News article URL
     * @return Extracted article text (up to 5000 chars), or null if failed
     */
    public String extractArticleContent(String url) {
        log.info("Extracting article content: {}", url);

        BrowserContext context = null;
        Page page = null;

        try {
            context = createStealthContext();
            page = context.newPage();

            // Random delay
            Thread.sleep(1000 + random.nextInt(2000));

            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(25000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            page.waitForTimeout(3000);  // Wait longer for content to load

            // Extract article content using common news article selectors
            Object evalResult = page.evaluate("""
                () => {
                    let allText = [];

                    // Get title first
                    const title = document.querySelector('h1.entry-title, h1.post-title, h1.article-title, h1')?.innerText || '';
                    if (title) {
                        allText.push('TITLE: ' + title.trim());
                    }

                    // Get meta description as fallback summary
                    const metaDesc = document.querySelector('meta[name="description"]')?.content ||
                                    document.querySelector('meta[property="og:description"]')?.content || '';
                    if (metaDesc) {
                        allText.push('SUMMARY: ' + metaDesc.trim());
                    }

                    // Remove non-content elements AFTER getting meta
                    ['script', 'style', 'nav', 'footer', 'header', 'aside', 'iframe', 'noscript',
                     '.sidebar', '.comments', '.related-posts', '.share-buttons', '.ad', '.advertisement',
                     '.wp-block-embed', '.social-share', '.author-bio', '.post-navigation',
                     '[class*="sidebar"]', '[class*="comment"]', '[class*="related"]', '[class*="share"]',
                     '[class*="newsletter"]', '[class*="subscription"]', '[class*="popup"]',
                     '[class*="widget"]', '[class*="advert"]', '[class*="promo"]'].forEach(selector => {
                        try {
                            document.querySelectorAll(selector).forEach(el => el.remove());
                        } catch(e) {}
                    });

                    // Article content selectors (prioritized for WordPress/news sites)
                    const articleSelectors = [
                        // WordPress common
                        '.entry-content',
                        '.post-content',
                        '.article-content',
                        '.td-post-content',
                        '.single-post-content',
                        // Generic article
                        'article .content',
                        'article',
                        '.article-body',
                        '.story-content',
                        '.post-body',
                        // Structured data
                        '[itemprop="articleBody"]',
                        // Fallbacks
                        'main article',
                        'main .post',
                        'main',
                        '#main-content',
                        '#content',
                        '.content-area'
                    ];

                    let foundContent = false;
                    for (const selector of articleSelectors) {
                        try {
                            const el = document.querySelector(selector);
                            if (el) {
                                // Get all paragraph text
                                const paragraphs = el.querySelectorAll('p');
                                let pText = '';
                                paragraphs.forEach(p => {
                                    const text = p.innerText?.trim();
                                    if (text && text.length > 20) {
                                        pText += text + ' ';
                                    }
                                });

                                if (pText.length > 200) {
                                    allText.push(pText.trim());
                                    foundContent = true;
                                    break;
                                }

                                // Fallback to full element text
                                const fullText = el.innerText || el.textContent;
                                if (fullText && fullText.trim().length > 200) {
                                    allText.push(fullText.trim());
                                    foundContent = true;
                                    break;
                                }
                            }
                        } catch(e) {}
                    }

                    // If still no content, try getting all paragraphs from body
                    if (!foundContent) {
                        const allParagraphs = document.querySelectorAll('body p');
                        let bodyText = '';
                        allParagraphs.forEach(p => {
                            const text = p.innerText?.trim();
                            if (text && text.length > 30) {
                                bodyText += text + ' ';
                            }
                        });
                        if (bodyText.length > 200) {
                            allText.push(bodyText.trim());
                        }
                    }

                    return allText.join(' --- ');
                }
                """);

            // Debug: log what we got back
            log.info("Playwright evaluate returned type: {}, value preview: {}",
                    evalResult != null ? evalResult.getClass().getSimpleName() : "null",
                    evalResult != null ? (evalResult.toString().length() > 100 ?
                            evalResult.toString().substring(0, 100) + "..." : evalResult.toString()) : "null");

            String articleText = evalResult != null ? evalResult.toString() : "";

            // Clean and limit
            articleText = articleText.replaceAll("\\s+", " ").trim();
            if (articleText.length() > 5000) {
                articleText = articleText.substring(0, 5000) + "...";
            }

            // If still too short, log warning
            if (articleText.length() < 100) {
                log.warn("Article extraction returned very short content ({} chars) for: {}", articleText.length(), url);
            }

            log.info("Extracted article ({} chars): {}", articleText.length(), url);
            return articleText.length() > 50 ? articleText : null;

        } catch (Exception e) {
            log.error("Failed to extract article from {}: {}", url, e.getMessage());
            return null;
        } finally {
            try {
                if (page != null && !page.isClosed()) page.close();
                if (context != null) context.close();
            } catch (Exception e) {
                // Ignore
            }
        }
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

    /**
     * Fetch XML/HTML content using Playwright (bypasses Cloudflare protection).
     * Used as fallback when Jsoup gets 403 errors from Cloudflare-protected sites.
     *
     * @param url URL to fetch (typically a sitemap URL)
     * @return Raw content as String, or null if failed
     */
    public String fetchPageContent(String url) {
        log.info("Fetching page content via Playwright: {}", url);

        int maxRetries = 2;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            BrowserContext context = null;
            Page page = null;

            try {
                context = createStealthContext();
                page = context.newPage();

                // Small delay to simulate human behavior
                Thread.sleep(500 + random.nextInt(1000));

                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(30000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                // Get the page content
                String content = page.content();

                if (content != null && !content.isEmpty()) {
                    log.info("Successfully fetched {} chars via Playwright: {}", content.length(), url);
                    return content;
                }

            } catch (Exception e) {
                log.error("Playwright fetch attempt {}/{} failed for {}: {}", attempt, maxRetries, url, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            } finally {
                try {
                    if (page != null && !page.isClosed()) page.close();
                    if (context != null) context.close();
                } catch (Exception e) {
                    // Ignore cleanup errors
                }
            }
        }
        return null;
    }
}
