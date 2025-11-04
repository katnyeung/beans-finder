package com.coffee.beansfinder.service;

import com.coffee.beansfinder.config.PerplexityProperties;
import com.coffee.beansfinder.dto.ExtractionResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Client for Perplexity API to extract structured coffee product data
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PerplexityApiClient {

    private final PerplexityProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build();

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * Extract structured coffee product data from HTML content or URL
     */
    public ExtractionResponse extractCoffeeData(String brand, String productName, String content)
            throws IOException {

        String prompt = buildExtractionPrompt(brand, productName, content);

        // Build request payload for Perplexity API
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", properties.getModel());
        payload.put("messages", new Object[]{
            Map.of("role", "system", "content", "You are a coffee data extraction assistant. " +
                "Extract structured information from coffee product descriptions and return valid JSON only."),
            Map.of("role", "user", "content", prompt)
        });

        // Enable structured output if supported
        payload.put("response_format", Map.of("type", "json_object"));
        payload.put("temperature", 0.1); // Low temperature for consistent extraction

        String jsonPayload = objectMapper.writeValueAsString(payload);

        log.debug("Sending request to Perplexity API for: {} - {}", brand, productName);

        RequestBody body = RequestBody.create(jsonPayload, JSON);
        Request request = new Request.Builder()
            .url(properties.getBaseUrl() + "/chat/completions")
            .addHeader("Authorization", "Bearer " + properties.getApiKey())
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No error body";
                throw new IOException("Perplexity API request failed: " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body().string();
            log.debug("Received response from Perplexity API");

            return parseExtractionResponse(responseBody);
        }
    }

    /**
     * Search for a coffee product online and extract its details
     */
    public ExtractionResponse searchAndExtract(String brand, String productName) throws IOException {
        String searchPrompt = String.format(
            "Find the official current product page for '%s' from '%s' coffee roaster in the UK. " +
            "Then extract and return the following information in JSON format:\n" +
            "{\n" +
            "  \"product_name\": \"exact product name\",\n" +
            "  \"origin\": \"country of origin\",\n" +
            "  \"region\": \"specific region if available\",\n" +
            "  \"process\": \"processing method (e.g., washed, natural, honey, anaerobic)\",\n" +
            "  \"producer\": \"farm or producer name\",\n" +
            "  \"variety\": \"coffee variety (e.g., Geisha, Bourbon, Caturra)\",\n" +
            "  \"altitude\": \"growing altitude\",\n" +
            "  \"tasting_notes\": [\"note1\", \"note2\", \"note3\"],\n" +
            "  \"price\": 0.0,\n" +
            "  \"in_stock\": true,\n" +
            "  \"sca_mapping\": {\n" +
            "    \"fruity\": [\"specific fruits mentioned\"],\n" +
            "    \"floral\": [\"floral notes\"],\n" +
            "    \"sweet\": [\"sweetness descriptors\"],\n" +
            "    \"other\": [\"other notes\"]\n" +
            "  }\n" +
            "}\n" +
            "Map tasting notes to SCA flavor wheel categories (fruity, floral, sweet, nutty, spices, roasted, green, sour, other).",
            productName, brand
        );

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", properties.getModel());
        payload.put("messages", new Object[]{
            Map.of("role", "user", "content", searchPrompt)
        });
        payload.put("response_format", Map.of("type", "json_object"));
        payload.put("temperature", 0.1);

        String jsonPayload = objectMapper.writeValueAsString(payload);

        RequestBody body = RequestBody.create(jsonPayload, JSON);
        Request request = new Request.Builder()
            .url(properties.getBaseUrl() + "/chat/completions")
            .addHeader("Authorization", "Bearer " + properties.getApiKey())
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Perplexity search failed: " + response.code());
            }

            String responseBody = response.body().string();
            return parseExtractionResponse(responseBody);
        }
    }

    private String buildExtractionPrompt(String brand, String productName, String content) {
        return String.format(
            "Extract coffee product information from the following content for '%s' by '%s'.\n\n" +
            "Content:\n%s\n\n" +
            "Return ONLY a valid JSON object with this exact structure (no additional text):\n" +
            "{\n" +
            "  \"product_name\": \"exact product name\",\n" +
            "  \"origin\": \"country of origin\",\n" +
            "  \"region\": \"specific region\",\n" +
            "  \"process\": \"processing method\",\n" +
            "  \"producer\": \"producer name\",\n" +
            "  \"variety\": \"coffee variety\",\n" +
            "  \"altitude\": \"altitude\",\n" +
            "  \"tasting_notes\": [\"list\", \"of\", \"notes\"],\n" +
            "  \"price\": 0.0,\n" +
            "  \"in_stock\": true,\n" +
            "  \"sca_mapping\": {\n" +
            "    \"fruity\": [],\n" +
            "    \"floral\": [],\n" +
            "    \"sweet\": [],\n" +
            "    \"nutty\": [],\n" +
            "    \"spices\": [],\n" +
            "    \"roasted\": [],\n" +
            "    \"other\": []\n" +
            "  }\n" +
            "}\n" +
            "Map tasting notes to appropriate SCA flavor wheel categories. Use null for missing fields.",
            productName, brand, content.substring(0, Math.min(content.length(), 4000))
        );
    }

    private ExtractionResponse parseExtractionResponse(String responseBody) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.get("choices");

        if (choices == null || choices.isEmpty()) {
            throw new JsonProcessingException("No choices in response") {};
        }

        JsonNode message = choices.get(0).get("message");
        String content = message.get("content").asText();

        // Parse the extracted data
        return objectMapper.readValue(content, ExtractionResponse.class);
    }
}
