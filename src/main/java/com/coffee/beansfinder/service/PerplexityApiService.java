package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.ExtractedProductData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Service for integrating with Perplexity API to extract structured coffee product data
 */
@Service
@Slf4j
public class PerplexityApiService {

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private String promptTemplate;

    @Value("${perplexity.api.key:}")
    private String apiKey;

    @Value("${perplexity.api.url}")
    private String apiUrl;

    @Value("${perplexity.api.model}")
    private String model;

    @Value("${crawler.retry.attempts:3}")
    private int retryAttempts;

    public PerplexityApiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        loadPromptTemplate();
    }

    /**
     * Filter URLs to identify which are coffee bean products vs equipment/accessories
     * Uses Perplexity to classify URLs based on their path and structure
     */
    public List<String> filterCoffeeBeanUrls(List<String> urls, String brandName) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Perplexity API key not configured, returning all URLs");
            return urls;
        }

        if (urls.isEmpty()) {
            return urls;
        }

        log.info("Asking Perplexity to filter {} URLs for coffee bean products", urls.size());

        try {
            // Build prompt with all URLs
            StringBuilder urlList = new StringBuilder();
            for (int i = 0; i < urls.size(); i++) {
                urlList.append(i + 1).append(". ").append(urls.get(i)).append("\n");
            }

            String prompt = String.format(
                    "Analyze these product URLs from %s. Return ONLY a JSON array of numbers for coffee bean/ground coffee products.\n\n" +
                    "INCLUDE (coffee beans to brew):\n" +
                    "- URLs with: coffee, beans, blend, origin, espresso, filter, roast, arabica, robusta\n" +
                    "- Country names (ethiopia, colombia, brazil, kenya)\n" +
                    "EXCLUDE (equipment/accessories):\n" +
                    "- URLs with: grinder, machine, kettle, scale, filter-paper, cup, mug, tool, equipment\n" +
                    "- Cleaning: urnex, cafiza, pallo, cleaner\n" +
                    "- Accessories: acaia, vst, wilfa, hario, kalita\n" +
                    "- Gift cards, subscriptions, bundles, merchandise\n\n" +
                    "URLs:\n%s\n" +
                    "OUTPUT FORMAT: [1, 3, 5, 7, 9]\n" +
                    "NO explanations. NO markdown. JUST the JSON array of numbers.",
                    brandName, urlList
            );

            String response = callPerplexityApi(prompt);
            log.info("=== PERPLEXITY FILTER RAW RESPONSE ===");
            log.info("Response: {}", response.length() > 500 ? response.substring(0, 500) + "..." : response);

            // Try to parse as API response first (with "choices" wrapper)
            String content;
            try {
                JsonNode root = objectMapper.readTree(response);
                if (root.has("choices") && root.get("choices").size() > 0) {
                    content = root.get("choices").get(0).get("message").get("content").asText();
                    log.info("=== PERPLEXITY FILTER CONTENT (from choices) ===");
                } else {
                    // Response might be the array directly
                    content = response;
                    log.info("=== PERPLEXITY FILTER CONTENT (direct) ===");
                }
            } catch (Exception e) {
                // If JSON parsing fails, treat response as direct content
                content = response;
                log.info("=== PERPLEXITY FILTER CONTENT (fallback) ===");
            }
            log.info("Content: {}", content.length() > 1000 ? content.substring(0, 1000) + "..." : content);

            // Parse the JSON array of indices
            String cleaned = content.trim();
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            }
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();

            JsonNode indices = objectMapper.readTree(cleaned);

            List<String> filteredUrls = new ArrayList<>();
            for (JsonNode indexNode : indices) {
                int index = indexNode.asInt() - 1; // Convert 1-based to 0-based
                if (index >= 0 && index < urls.size()) {
                    filteredUrls.add(urls.get(index));
                }
            }

            log.info("Perplexity filtered: {} coffee bean URLs out of {} total URLs",
                    filteredUrls.size(), urls.size());

            return filteredUrls;

        } catch (Exception e) {
            log.error("Error filtering URLs with Perplexity: {}", e.getMessage(), e);
            return urls; // Return all URLs if filtering fails
        }
    }

    /**
     * Load the extraction prompt template from file
     */
    private void loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/product_extraction_prompt.txt");
            this.promptTemplate = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.info("Loaded prompt template from prompts/product_extraction_prompt.txt");
        } catch (IOException e) {
            log.error("Failed to load prompt template, using fallback", e);
            // Fallback to basic prompt if file not found
            this.promptTemplate = """
                Extract structured coffee product information from the following content.

                Brand: {BRAND_NAME}
                URL: {PRODUCT_URL}
                Content: {RAW_CONTENT}

                Return a JSON object with: product_name, origin, region, process, producer, variety, altitude, tasting_notes, price, in_stock
                """;
        }
    }

    /**
     * Extract structured product data from raw HTML/text using Perplexity API
     */
    public ExtractedProductData extractProductData(String rawContent, String brandName, String productUrl) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Perplexity API key not configured, returning empty data");
            return ExtractedProductData.builder().build();
        }

        String prompt = buildExtractionPrompt(rawContent, brandName, productUrl);

        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                log.info("Attempting Perplexity API call (attempt {}/{}) for brand: {}",
                        attempt, retryAttempts, brandName);

                String response = callPerplexityApi(prompt);
                ExtractedProductData data = parseResponse(response);

                log.info("Successfully extracted product data: {}", data.getProductName());
                return data;

            } catch (Exception e) {
                log.error("Perplexity API call failed (attempt {}/{}): {}",
                        attempt, retryAttempts, e.getMessage());

                if (attempt == retryAttempts) {
                    log.error("All retry attempts exhausted for brand: {}", brandName);
                    return ExtractedProductData.builder()
                            .productName("Unknown")
                            .tastingNotes(new ArrayList<>())
                            .build();
                }

                // Exponential backoff
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        return ExtractedProductData.builder().build();
    }

    /**
     * Build extraction prompt for Perplexity API using template from file
     */
    private String buildExtractionPrompt(String rawContent, String brandName, String productUrl) {
        return promptTemplate
                .replace("{BRAND_NAME}", brandName)
                .replace("{PRODUCT_URL}", productUrl)
                .replace("{RAW_CONTENT}", rawContent);
    }

    /**
     * Call Perplexity API with the given prompt
     * Uses optimized parameters for web search and extraction
     */
    private String callPerplexityApi(String prompt) throws IOException {
        return callPerplexityApi(prompt, null);
    }

    /**
     * Call Perplexity API with domain filter
     */
    private String callPerplexityApi(String prompt, String domainFilter) throws IOException {
        // Use sonar-pro for complex queries requiring deeper research
        String onlineModel = "sonar-pro";

        // Build domain filter if provided
        String domainFilterJson = domainFilter != null
                ? String.format("""
                  "search_domain_filter": ["%s"],""", domainFilter)
                : "";

        String requestBody = String.format("""
                {
                  "model": "%s",
                  "messages": [
                    {
                      "role": "system",
                      "content": "You are a data extraction assistant. Extract structured JSON data from coffee product descriptions. Always return valid JSON only."
                    },
                    {
                      "role": "user",
                      "content": "%s"
                    }
                  ],
                  "temperature": 0.1,
                  "max_tokens": 4000,
                  %s
                  "return_citations": false,
                  "return_images": false,
                  "search_recency_filter": "month"
                }
                """, onlineModel, escapeJson(prompt), domainFilterJson);

        log.debug("API URL: {}", apiUrl);
        log.debug("Model: {}", model);
        log.debug("Request body length: {} chars", requestBody.length());

        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, MediaType.get("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            log.debug("Response code: {}", response.code());

            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No error body";
                log.error("API Error Response: {}", errorBody);
                throw new IOException("Perplexity API call failed: " + response.code() + " " + response.message());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response from Perplexity API");
            }

            String responseBody = body.string();
            log.debug("Full API response: {}", responseBody);

            // Parse response to get the content
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode contentNode = rootNode.path("choices").get(0).path("message").path("content");

            String content = contentNode.asText();
            log.debug("Extracted content length: {} chars", content.length());

            return content;
        }
    }

    /**
     * Parse Perplexity response into ExtractedProductData
     */
    private ExtractedProductData parseResponse(String response) throws IOException {
        // Clean response - sometimes LLMs wrap JSON in markdown code blocks
        String cleanedResponse = response.trim();
        if (cleanedResponse.startsWith("```json")) {
            cleanedResponse = cleanedResponse.substring(7);
        }
        if (cleanedResponse.startsWith("```")) {
            cleanedResponse = cleanedResponse.substring(3);
        }
        if (cleanedResponse.endsWith("```")) {
            cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
        }
        cleanedResponse = cleanedResponse.trim();

        log.debug("Parsing cleaned response: {}", cleanedResponse);

        return objectMapper.readValue(cleanedResponse, ExtractedProductData.class);
    }

    /**
     * Escape JSON string for safe embedding
     */
    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Generate a list of specialty coffee roasters using Perplexity
     * @param country Country to search for roasters
     * @param limit Number of brands to generate
     * @param excludeBrands List of brand names already in database to exclude
     * @param suggestedBrands List of suggested brand names to use as examples/guidelines
     */
    public List<String> generateBrandsList(String country, int limit, List<String> excludeBrands, List<String> suggestedBrands) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Perplexity API key not configured");
            return new ArrayList<>();
        }

        // Build suggested brands text (as examples/guidelines)
        String suggestedText = "";
        if (suggestedBrands != null && !suggestedBrands.isEmpty()) {
            suggestedText = String.format("""

                SUGGESTED BRANDS (use these as examples/reference):
                %s

                Use these brands as a guideline for the type and quality of roasters to suggest.
                Include similar specialty coffee roasters.
                """, String.join(", ", suggestedBrands));
        }

        // Build exclusion text
        String exclusionText = "";
        if (excludeBrands != null && !excludeBrands.isEmpty()) {
            exclusionText = String.format("""

                IMPORTANT: Exclude these brands that are already in our database:
                %s

                Only suggest NEW brands that are NOT in the exclusion list above.
                """, String.join(", ", excludeBrands));
        }

        String prompt = String.format("""
                List %d popular specialty coffee roasters in %s.
                %s%s
                Return ONLY a JSON array of brand names as strings.
                Example format: ["Brand One", "Brand Two", "Brand Three"]

                Focus on specialty coffee roasters that sell coffee beans online.
                No explanations, just the JSON array.
                """, limit, country, suggestedText, exclusionText);

        try {
            String response = callPerplexityApi(prompt);
            String cleanedResponse = response.trim();

            // Clean markdown code blocks if present
            if (cleanedResponse.startsWith("```json")) {
                cleanedResponse = cleanedResponse.substring(7);
            }
            if (cleanedResponse.startsWith("```")) {
                cleanedResponse = cleanedResponse.substring(3);
            }
            if (cleanedResponse.endsWith("```")) {
                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
            }
            cleanedResponse = cleanedResponse.trim();

            // Parse as list of strings
            List<String> brands = objectMapper.readValue(
                cleanedResponse,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
            );

            log.info("Generated {} brand names for {} (suggested: {}, excluded: {})",
                     brands.size(), country,
                     suggestedBrands != null ? suggestedBrands.size() : 0,
                     excludeBrands != null ? excludeBrands.size() : 0);
            return brands;

        } catch (Exception e) {
            log.error("Failed to generate brands list: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Extract product data from a list of product URLs using Perplexity
     * More efficient - sends all URLs at once
     */
    public List<ExtractedProductData> extractProductsFromUrls(String brandName, List<String> productUrls) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Perplexity API key not configured");
            return new ArrayList<>();
        }

        if (productUrls.isEmpty()) {
            log.warn("No product URLs provided");
            return new ArrayList<>();
        }

        // Limit to first 10-15 URLs to avoid token limits
        List<String> limitedUrls = productUrls.stream().limit(15).toList();

        // Build concise URLs list
        String urlsContext = String.join(", ", limitedUrls);

        String prompt = String.format("""
                Extract DETAILED coffee product information from %s as of 2025. Visit these product URLs:

                %s

                For EACH product (aim for %d), extract ALL available details and return a JSON array:
                [
                  {
                    "product_name": "Full product name",
                    "product_url": "Exact product page URL",
                    "origin": "Country or Blend",
                    "region": "Farm/Region/Area",
                    "process": "Processing method (Washed/Natural/Honey/Anaerobic/etc.)",
                    "producer": "Farm/Producer name",
                    "variety": "Coffee variety (Arabica/Bourbon/Geisha/etc.)",
                    "altitude": "Altitude in MASL or meters",
                    "tasting_notes": ["ALL flavor notes - include taste, sweetness, acidity, mouthfeel, body"],
                    "price": 9.95,
                    "in_stock": true,
                    "raw_description": "Full product description text as shown on page - include all sections like About This Coffee, tasting notes details, origin story, etc."
                  }
                ]

                EXTRACTION REQUIREMENTS:
                - Match each product to its source URL from the list above
                - Include ALL tasting notes/flavor descriptors found on the page (look for: Taste, Sweetness, Acidity, Mouthfeel, Body, Notes sections)
                - Capture detailed attributes like roast level, grind options if shown
                - Extract exact altitude/elevation if provided
                - Use actual 2025 data from product pages
                - Return empty array [] if no products found

                Be comprehensive - extract every flavor descriptor and attribute visible on the product page.
                """, brandName, urlsContext, limitedUrls.size());

        // Extract domain from first URL for filtering
        String domain = null;
        try {
            java.net.URI uri = new java.net.URI(limitedUrls.get(0));
            domain = uri.getHost();
            if (domain.startsWith("www.")) {
                domain = domain.substring(4);
            }
        } catch (Exception e) {
            log.warn("Could not extract domain from URL: {}", limitedUrls.get(0));
        }

        log.info("=== PERPLEXITY BATCH REQUEST ===");
        log.info("Brand: {}", brandName);
        log.info("Number of URLs: {} (limited to {})", productUrls.size(), limitedUrls.size());
        log.info("Domain filter: {}", domain);
        log.info("First URL: {}", productUrls.get(0));
        log.info("Prompt: {}", prompt);

        try {
            String response = callPerplexityApi(prompt, domain);

            log.info("=== PERPLEXITY RAW RESPONSE ===");
            log.info("Response: {}", response);

            String cleanedResponse = response.trim();

            // Clean markdown code blocks if present
            if (cleanedResponse.startsWith("```json")) {
                cleanedResponse = cleanedResponse.substring(7);
            }
            if (cleanedResponse.startsWith("```")) {
                cleanedResponse = cleanedResponse.substring(3);
            }
            if (cleanedResponse.endsWith("```")) {
                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
            }
            cleanedResponse = cleanedResponse.trim();

            log.info("=== CLEANED RESPONSE ===");
            log.info("Cleaned: {}", cleanedResponse);

            // Parse as list of ExtractedProductData
            List<ExtractedProductData> products = objectMapper.readValue(
                cleanedResponse,
                objectMapper.getTypeFactory().constructCollectionType(List.class, ExtractedProductData.class)
            );

            log.info("=== PARSING RESULT ===");
            log.info("Discovered {} products for brand: {} from {} URLs",
                     products.size(), brandName, productUrls.size());
            if (!products.isEmpty()) {
                log.info("First product: {}", products.get(0).getProductName());
            }
            return products;

        } catch (Exception e) {
            log.error("=== PERPLEXITY ERROR ===");
            log.error("Failed to extract products from URLs for {}: {}", brandName, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Discover all coffee products for a brand using Perplexity (DEPRECATED - use extractProductsFromUrls)
     */
    @Deprecated
    public List<ExtractedProductData> discoverBrandProducts(String brandName, String brandWebsite, String sitemapUrl) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Perplexity API key not configured");
            return new ArrayList<>();
        }

        // Build sitemap hint if available
        String sitemapHint = "";
        if (sitemapUrl != null && !sitemapUrl.isEmpty()) {
            sitemapHint = String.format("""

                IMPORTANT: The brand has a sitemap at: %s
                Check this sitemap to find all product URLs, then visit those URLs to extract the product details.
                """, sitemapUrl);
        }

        String prompt = String.format("""
                Search the website %s and find ALL current coffee bean products available from "%s".
                %s
                Look for their products page, shop page, or coffee catalog. Common URLs to check:
                - %s/products
                - %s/shop
                - %s/coffees
                - %s/coffee

                For EACH coffee product you find, extract the following information and return as a JSON array:
                [
                  {
                    "product_name": "Product name",
                    "origin": "Country of origin",
                    "region": "Specific region/farm",
                    "process": "Processing method (Washed, Natural, Honey, Anaerobic, etc.)",
                    "producer": "Farm/Producer name",
                    "variety": "Coffee variety (Geisha, Caturra, etc.)",
                    "altitude": "Growing altitude",
                    "tasting_notes": ["note1", "note2", "note3"],
                    "price": 15.50,
                    "in_stock": true
                  }
                ]

                Important:
                - SEARCH the website RIGHT NOW to get current products
                - Find ALL available coffee products from their catalog
                - Include accurate tasting notes and origin details from the product descriptions
                - Set price to null if not available
                - Return ONLY the JSON array with real current products, no explanations
                - If you cannot access the website or find products, return empty array []
                """, brandWebsite, brandName, sitemapHint, brandWebsite, brandWebsite, brandWebsite, brandWebsite);

        log.info("=== PERPLEXITY REQUEST ===");
        log.info("Brand: {}", brandName);
        log.info("Website: {}", brandWebsite);
        log.info("Sitemap URL: {}", sitemapUrl != null ? sitemapUrl : "Not provided");
        log.info("Prompt: {}", prompt);

        try {
            String response = callPerplexityApi(prompt);

            log.info("=== PERPLEXITY RAW RESPONSE ===");
            log.info("Response: {}", response);

            String cleanedResponse = response.trim();

            // Clean markdown code blocks if present
            if (cleanedResponse.startsWith("```json")) {
                cleanedResponse = cleanedResponse.substring(7);
            }
            if (cleanedResponse.startsWith("```")) {
                cleanedResponse = cleanedResponse.substring(3);
            }
            if (cleanedResponse.endsWith("```")) {
                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
            }
            cleanedResponse = cleanedResponse.trim();

            log.info("=== CLEANED RESPONSE ===");
            log.info("Cleaned: {}", cleanedResponse);

            // Parse as list of ExtractedProductData
            List<ExtractedProductData> products = objectMapper.readValue(
                cleanedResponse,
                objectMapper.getTypeFactory().constructCollectionType(List.class, ExtractedProductData.class)
            );

            log.info("=== PARSING RESULT ===");
            log.info("Discovered {} products for brand: {}", products.size(), brandName);
            if (!products.isEmpty()) {
                log.info("First product: {}", products.get(0).getProductName());
            }
            return products;

        } catch (Exception e) {
            log.error("=== PERPLEXITY ERROR ===");
            log.error("Failed to discover products for {}: {}", brandName, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Auto-discover brand details using Perplexity
     */
    public BrandDetails discoverBrandDetails(String brandName) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Perplexity API key not configured");
            return null;
        }

        String prompt = String.format("""
                Find detailed information about the coffee roaster: "%s"

                Return a JSON object with the following fields:
                {
                  "name": "Official brand name",
                  "website": "Official website URL",
                  "sitemapUrl": "Sitemap URL",
                  "country": "Country code (e.g., UK, US, etc.)",
                  "description": "Brief description of the roaster (1-2 sentences)"
                }

                Important for sitemapUrl:
                - PREFER product-specific sitemaps if available:
                  * sitemap_products_1.xml (Shopify pattern, may include ?from=X&to=Y query params)
                  * product-sitemap.xml (WooCommerce pattern)
                  * Check the main sitemap.xml and extract product sitemap URL from the index
                - If no product-specific sitemap found, use main sitemap.xml
                - Common locations: /sitemap.xml, /sitemap_index.xml, /product-sitemap.xml

                Other requirements:
                - Verify the brand exists
                - Use official website only
                - Return ONLY valid JSON, no explanations
                - If brand not found, return {"name": null}
                """, brandName);

        try {
            String response = callPerplexityApi(prompt);
            String cleanedResponse = response.trim();

            // Clean markdown code blocks if present
            if (cleanedResponse.startsWith("```json")) {
                cleanedResponse = cleanedResponse.substring(7);
            }
            if (cleanedResponse.startsWith("```")) {
                cleanedResponse = cleanedResponse.substring(3);
            }
            if (cleanedResponse.endsWith("```")) {
                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
            }
            cleanedResponse = cleanedResponse.trim();

            BrandDetails details = objectMapper.readValue(cleanedResponse, BrandDetails.class);
            log.info("Discovered brand details for: {}", brandName);
            return details;

        } catch (Exception e) {
            log.error("Failed to discover brand details for {}: {}", brandName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * DTO for brand details discovered by Perplexity
     */
    public static class BrandDetails {
        public String name;
        public String website;
        public String sitemapUrl;
        public String country;
        public String description;
    }

    /**
     * Search for product URL using Perplexity
     */
    public String searchProductUrl(String brandName, String productName) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Perplexity API key not configured");
            return null;
        }

        String searchPrompt = String.format("""
                Find the official current product page URL for the following coffee:
                Brand: %s
                Product: %s

                Return ONLY the URL, nothing else. If not found, return "NOT_FOUND".
                Prefer official brand websites or major UK coffee retailers.
                """, brandName, productName);

        try {
            return callPerplexityApi(searchPrompt).trim();
        } catch (Exception e) {
            log.error("Failed to search for product URL: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract product data from Playwright-rendered text/HTML
     * Used when site is JavaScript-rendered (Shopify, React, Next.js)
     * This replaces complex regex extraction with AI-powered extraction
     * Cost: ~$0.0005 per product (text) or ~$0.005 (full HTML)
     * Success rate: 80-90% (vs 20-30% with regex)
     *
     * @param renderedContent Either product text (10KB, preferred) or full HTML (100KB)
     * @param brandName Brand name
     * @param productUrl Product URL
     * @return Extracted product data
     */
    public ExtractedProductData extractFromRenderedHtml(
            String renderedContent,
            String brandName,
            String productUrl) {

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Perplexity API key not configured for HTML extraction");
            return null;
        }

        if (renderedContent == null || renderedContent.isEmpty()) {
            log.warn("Empty content provided for extraction");
            return null;
        }

        // Truncate if too large (Perplexity has 127K token context limit)
        // Approximate: 1 token ≈ 4 chars, so 40KB ≈ 10K tokens (safe margin)
        String contentSnippet = renderedContent.length() > 40000
                ? renderedContent.substring(0, 40000)
                : renderedContent;

        log.info("Extracting from rendered content ({} chars) for URL: {}", contentSnippet.length(), productUrl);

        String prompt = String.format("""
                Extract coffee product data from this product page text/content.

                Brand: %s
                URL: %s

                Page Content:
                %s

                Return ONLY a JSON object with this exact structure:
                {
                  "product_name": "string (required)",
                  "origin": "country or null",
                  "region": "farm/region or null",
                  "process": "Washed/Natural/Honey/Anaerobic/etc or null",
                  "producer": "producer/farm name or null",
                  "variety": "coffee variety like Bourbon/Geisha/Caturra or null",
                  "altitude": "altitude in MASL or meters or null",
                  "tasting_notes": ["flavor1", "flavor2", "flavor3"] or [],
                  "price": number or null,
                  "in_stock": boolean or null,
                  "raw_description": "full product description text"
                }

                Extraction guidelines:
                - Search ENTIRE HTML for product info: meta tags, JSON-LD, <script> tags, text content, data attributes
                - Tasting notes: look for flavors, taste descriptors, cupping notes
                - Origin: country name (e.g., Ethiopia, Colombia, Brazil)
                - Process: common methods are Washed, Natural, Honey, Anaerobic, Carbonic Maceration
                - Extract ALL tasting notes/flavors mentioned anywhere on the page
                - Price: convert to decimal number, remove currency symbols
                - raw_description: capture the full product description text (not just meta description)

                Return ONLY valid JSON, no markdown code blocks, no explanations.
                """, brandName, productUrl, contentSnippet);

        try {
            String response = callPerplexityApi(prompt);

            log.info("=== PERPLEXITY HTML EXTRACTION RAW RESPONSE ===");
            log.info("Response length: {} chars", response.length());
            log.debug("Response: {}", response.length() > 500 ? response.substring(0, 500) + "..." : response);

            // Clean response
            String cleanedResponse = response.trim();
            if (cleanedResponse.startsWith("```json")) {
                cleanedResponse = cleanedResponse.substring(7);
            }
            if (cleanedResponse.startsWith("```")) {
                cleanedResponse = cleanedResponse.substring(3);
            }
            if (cleanedResponse.endsWith("```")) {
                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
            }
            cleanedResponse = cleanedResponse.trim();

            log.info("=== CLEANED RESPONSE ===");
            log.debug("Cleaned: {}", cleanedResponse.length() > 500 ? cleanedResponse.substring(0, 500) + "..." : cleanedResponse);

            // Parse JSON
            ExtractedProductData extracted = objectMapper.readValue(
                    cleanedResponse,
                    ExtractedProductData.class
            );

            log.info("=== EXTRACTION SUCCESS ===");
            log.info("Product: {}", extracted.getProductName());
            log.info("Origin: {}, Process: {}, Tasting Notes: {}",
                    extracted.getOrigin(),
                    extracted.getProcess(),
                    extracted.getTastingNotes() != null ? extracted.getTastingNotes().size() : 0);

            return extracted;

        } catch (Exception e) {
            log.error("=== PERPLEXITY HTML EXTRACTION ERROR ===");
            log.error("Failed to extract from rendered HTML: {}", e.getMessage(), e);
            return null;
        }
    }
}
