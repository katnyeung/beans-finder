package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.ExtractedProductData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * OpenAI GPT-4o-mini service for text extraction
 * 20x cheaper than Perplexity for extraction tasks
 * Cost: ~$0.15/$0.60 per 1M tokens (input/output)
 * Use case: Extract structured data from Playwright-rendered text
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OpenAIService {

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.api.url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${openai.model:gpt-4o-mini}")
    private String model;

    @Value("${openai.temperature:0.1}")
    private double temperature;

    @Value("${openai.max.tokens:1000}")
    private int maxTokens;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Extract product data from text content using GPT-4o-mini
     * Cost: ~$0.0004 per product (20x cheaper than Perplexity)
     *
     * @param productText Clean product text from Playwright (10KB)
     * @param brandName Brand name
     * @param productUrl Product URL
     * @return Extracted product data
     */
    public ExtractedProductData extractFromText(
            String productText,
            String brandName,
            String productUrl) {

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("OpenAI API key not configured");
            return null;
        }

        if (productText == null || productText.isEmpty()) {
            log.warn("Empty text provided for extraction");
            return null;
        }

        // Truncate if too large (GPT-4o-mini has 128K context, but we want to keep costs low)
        // 20KB ≈ 5,000 tokens (allows for more detailed product descriptions like Scenery Coffee)
        // Cost impact: ~$0.0008 per product (still 10x cheaper than Perplexity)
        String textSnippet = productText.length() > 20000
                ? productText.substring(0, 20000)
                : productText;

        log.info("Extracting from text ({} chars) using OpenAI: {}", textSnippet.length(), productUrl);

        try {
            String prompt = buildExtractionPrompt(textSnippet, brandName, productUrl);
            String response = callOpenAI(prompt);

            log.info("=== OPENAI RAW RESPONSE ===");
            log.info("Response length: {} chars", response.length());
            log.debug("Response: {}", response.length() > 500 ? response.substring(0, 500) + "..." : response);

            // Parse JSON response
            ExtractedProductData extracted = objectMapper.readValue(response, ExtractedProductData.class);

            // Set the product URL (OpenAI doesn't extract this, we provide it)
            extracted.setProductUrl(productUrl);

            log.info("=== EXTRACTION SUCCESS ===");
            log.info("Product: {}", extracted.getProductName());
            log.info("Origin: {}, Process: {}, Tasting Notes: {}",
                    extracted.getOrigin(),
                    extracted.getProcess(),
                    extracted.getTastingNotes() != null ? extracted.getTastingNotes().size() : 0);

            return extracted;

        } catch (Exception e) {
            log.error("=== OPENAI EXTRACTION ERROR ===");
            log.error("Failed to extract from text: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Build extraction prompt for OpenAI
     */
    private String buildExtractionPrompt(String productText, String brandName, String productUrl) {
        return String.format("""
                Extract coffee product data from this product page text.

                Brand: %s
                URL: %s

                Page Text:
                %s

                Extract and return a JSON object with these fields:
                {
                  "product_name": "Full product name (required)",
                  "origin": "Country (e.g., Ethiopia, Colombia, Brazil) or null",
                  "region": "Farm/Region (e.g., Sidama, Huehuetenango) or null",
                  "process": "Processing method (Washed/Natural/Honey/Anaerobic/etc.) or null",
                  "producer": "Farm or producer name or null",
                  "variety": "Coffee variety (Bourbon/Geisha/Caturra/etc.) or null",
                  "altitude": "Altitude (e.g., '1,800 MASL', '2000-2200m') or null",
                  "tasting_notes": ["flavor1", "flavor2", "flavor3"] or [],
                  "price": 12.50 (number) or null,
                  "in_stock": true/false or null,
                  "raw_description": "Full product description text"
                }

                Extraction rules:
                - Extract ALL tasting notes/flavors mentioned (look for: taste, notes, flavor, cupping notes)
                - Include descriptors like sweetness, acidity, body, mouthfeel
                - price: Extract as decimal number, remove currency symbols (e.g., "£12.50" → 12.50)
                - raw_description: Capture the main product description text
                - Use null for missing fields, not empty strings
                - Return ONLY valid JSON, no markdown code blocks

                Return the JSON object:
                """, brandName, productUrl, productText);
    }

    /**
     * Call OpenAI API
     */
    public String callOpenAI(String prompt) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", "You are a coffee product data extraction assistant. Extract structured data from product pages and return valid JSON."),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", temperature,
                "max_tokens", maxTokens,
                "response_format", Map.of("type", "json_object") // Force JSON output
        );

        log.debug("Calling OpenAI API: model={}, temperature={}, max_tokens={}", model, temperature, maxTokens);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error("OpenAI API error: {}", response.getBody());
            throw new RuntimeException("OpenAI API returned error: " + response.getStatusCode());
        }

        // Extract content from response
        String responseBody = response.getBody();
        JsonNode root = objectMapper.readTree(responseBody);

        if (!root.has("choices") || root.get("choices").size() == 0) {
            throw new RuntimeException("No choices in OpenAI response");
        }

        JsonNode firstChoice = root.get("choices").get(0);
        if (!firstChoice.has("message") || !firstChoice.get("message").has("content")) {
            throw new RuntimeException("No content in OpenAI response");
        }

        String content = firstChoice.get("message").get("content").asText();

        log.debug("Extracted content from OpenAI response: {} chars", content.length());

        return content;
    }

    /**
     * Categorize coffee flavor notes into SCA categories using GPT-4o-mini
     * Processes flavors in batches for efficiency
     * Cost: ~$0.0001 per 10 flavors
     *
     * @param flavorNames List of flavor names to categorize
     * @return Map of flavor name -> SCA category
     */
    public Map<String, String> categorizeFlavors(List<String> flavorNames) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("OpenAI API key not configured");
        }

        String prompt = buildFlavorCategorizationPrompt(flavorNames);

        try {
            // Build request
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", "You are a coffee flavor expert familiar with the SCA (Specialty Coffee Association) flavor wheel."),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0.1,
                    "max_tokens", 500
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            log.info("Calling OpenAI to categorize {} flavors", flavorNames.size());

            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("OpenAI API returned status: " + response.getStatusCode());
            }

            // Extract content from OpenAI response
            String responseBody = response.getBody();
            JsonNode root = objectMapper.readTree(responseBody);

            if (!root.has("choices") || root.get("choices").size() == 0) {
                throw new RuntimeException("No choices in OpenAI response");
            }

            JsonNode firstChoice = root.get("choices").get(0);
            if (!firstChoice.has("message") || !firstChoice.get("message").has("content")) {
                throw new RuntimeException("No content in OpenAI response");
            }

            String jsonResponse = firstChoice.get("message").get("content").asText();

            // Clean markdown code blocks if present (```json ... ```)
            jsonResponse = cleanMarkdownCodeBlocks(jsonResponse);

            // Parse JSON response
            return objectMapper.readValue(jsonResponse, Map.class);

        } catch (Exception e) {
            log.error("Failed to categorize flavors with OpenAI: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to categorize flavors: " + e.getMessage(), e);
        }
    }

    /**
     * Clean markdown code blocks from response
     * Handles cases like: ```json\n{...}\n``` or ```{...}```
     */
    private String cleanMarkdownCodeBlocks(String text) {
        if (text == null) return null;

        // Remove markdown code blocks: ```json ... ``` or ``` ... ```
        text = text.trim();

        if (text.startsWith("```")) {
            // Find the first newline after opening ```
            int firstNewline = text.indexOf('\n');
            if (firstNewline != -1) {
                text = text.substring(firstNewline + 1);
            } else {
                // No newline, just remove ```
                text = text.substring(3);
            }

            // Remove closing ```
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }

            text = text.trim();
        }

        return text;
    }

    /**
     * Build prompt for flavor categorization
     */
    private String buildFlavorCategorizationPrompt(List<String> flavorNames) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Categorize these coffee tasting notes into ONE of the SCA flavor wheel categories:\n\n");
        prompt.append("Categories:\n");
        prompt.append("- fruity (berry, citrus, stone fruit, tropical)\n");
        prompt.append("- floral (jasmine, rose, chamomile, lavender)\n");
        prompt.append("- sweet (honey, caramel, vanilla, chocolate, maple)\n");
        prompt.append("- nutty (almond, hazelnut, peanut, walnut)\n");
        prompt.append("- spices (cinnamon, clove, pepper, cardamom)\n");
        prompt.append("- roasted (dark chocolate, tobacco, smoky, burnt)\n");
        prompt.append("- green (vegetative, herbal, grass, fresh)\n");
        prompt.append("- sour (acetic, vinegar, fermented, citric)\n");
        prompt.append("- other (if none of the above fit)\n\n");

        prompt.append("Flavor notes to categorize:\n");
        for (String flavor : flavorNames) {
            prompt.append("- ").append(flavor).append("\n");
        }

        prompt.append("\nReturn ONLY a valid JSON object mapping each flavor to its category.\n");
        prompt.append("Format: {\"flavor1\": \"category\", \"flavor2\": \"category\", ...}\n");
        prompt.append("Example: {\"strawberry\": \"fruity\", \"jasmine\": \"floral\", \"caramel\": \"sweet\"}\n");

        return prompt.toString();
    }
}
