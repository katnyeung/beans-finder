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
        // 10KB ≈ 2,500 tokens (safe for quick extraction)
        String textSnippet = productText.length() > 10000
                ? productText.substring(0, 10000)
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
    private String callOpenAI(String prompt) throws Exception {
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
}
