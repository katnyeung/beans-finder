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
     */
    private String callPerplexityApi(String prompt) throws IOException {
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
                  "temperature": 0.2,
                  "max_tokens": 1000
                }
                """, model, escapeJson(prompt));

        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, MediaType.get("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Perplexity API call failed: " + response.code() + " " + response.message());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response from Perplexity API");
            }

            String responseBody = body.string();
            log.debug("Perplexity API response: {}", responseBody);

            // Parse response to get the content
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode contentNode = rootNode.path("choices").get(0).path("message").path("content");

            return contentNode.asText();
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
}
