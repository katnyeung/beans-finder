package com.coffee.beansfinder.service;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Grok LLM service for chatbot RAG (Retrieval-Augmented Generation)
 * Uses X.AI's Grok API for conversational coffee recommendations
 * Cost: Similar to GPT-4o-mini (~$0.15/$0.60 per 1M tokens)
 * Use case: RAG-powered chatbot with Neo4j knowledge graph context
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GrokService {

    @Value("${grok.api.key:}")
    private String apiKey;

    @Value("${grok.api.url:https://api.x.ai/v1/chat/completions}")
    private String apiUrl;

    @Value("${grok.model:grok-beta}")
    private String model;

    @Value("${grok.temperature:0.3}")
    private double temperature;

    @Value("${grok.max.tokens:1500}")
    private int maxTokens;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Call Grok API with conversational memory
     * Supports multi-turn conversations by passing message history
     *
     * @param systemPrompt System-level instructions for the chatbot
     * @param conversationHistory List of previous messages (role + content)
     * @param userMessage Current user message
     * @return Grok's JSON response as String
     */
    public String callGrokWithHistory(
            String systemPrompt,
            List<Map<String, Object>> conversationHistory,
            String userMessage) throws Exception {

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("Grok API key not configured. Set GROK_API_KEY environment variable.");
        }

        log.info("Calling Grok API with {} history messages", conversationHistory.size());
        log.debug("User message: {}", userMessage.length() > 200 ? userMessage.substring(0, 200) + "..." : userMessage);

        // Build message list: system + history + current user message
        // Note: Filter out "products" field from history (Grok only needs text messages)
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // Add history messages (role + content only, skip products)
        for (Map<String, Object> historyMsg : conversationHistory) {
            messages.add(Map.of(
                "role", historyMsg.get("role").toString(),
                "content", historyMsg.get("content").toString()
            ));
        }

        messages.add(Map.of("role", "user", "content", userMessage));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", messages,
                "temperature", temperature,
                "max_tokens", maxTokens,
                "response_format", Map.of("type", "json_object") // Force JSON output
        );

        log.debug("Calling Grok API: model={}, temperature={}, max_tokens={}, messages={}",
                model, temperature, maxTokens, messages.size());

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Grok API error: status={}, body={}", response.getStatusCode(), response.getBody());
                throw new RuntimeException("Grok API returned error: " + response.getStatusCode());
            }

            // Extract content from response
            String responseBody = response.getBody();
            JsonNode root = objectMapper.readTree(responseBody);

            if (!root.has("choices") || root.get("choices").size() == 0) {
                throw new RuntimeException("No choices in Grok response");
            }

            JsonNode firstChoice = root.get("choices").get(0);
            if (!firstChoice.has("message") || !firstChoice.get("message").has("content")) {
                throw new RuntimeException("No content in Grok response");
            }

            String content = firstChoice.get("message").get("content").asText();

            log.info("Grok response received: {} chars", content.length());
            log.debug("Grok response: {}", content.length() > 500 ? content.substring(0, 500) + "..." : content);

            return content;

        } catch (Exception e) {
            log.error("Failed to call Grok API: {}", e.getMessage(), e);
            throw new RuntimeException("Grok API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Simple single-turn call to Grok (no conversation history)
     * Used for quick queries without context
     *
     * @param systemPrompt System instructions
     * @param userMessage User query
     * @return Grok's JSON response as String
     */
    public String callGrok(String systemPrompt, String userMessage) throws Exception {
        return callGrokWithHistory(systemPrompt, List.of(), userMessage);
    }

    /**
     * Check if Grok API is configured
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty();
    }
}
