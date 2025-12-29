package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.Crawl4AIResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client service for communicating with the Crawl4AI Python microservice.
 * Provides web crawling capabilities using Crawl4AI for LLM-friendly markdown extraction.
 */
@Service
public class CrawlClientService {

    private static final Logger logger = LoggerFactory.getLogger(CrawlClientService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    @Value("${crawl.service.url:http://localhost:8081}")
    private String crawlServiceUrl;

    @Value("${crawl.service.enabled:false}")
    private boolean enabled;

    @Value("${crawl.service.timeout-ms:30000}")
    private int timeoutMs;

    public CrawlClientService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Check if the Crawl4AI service is enabled via configuration.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Crawl a URL using the smart endpoint (Crawl4AI with Playwright fallback).
     *
     * @param url The URL to crawl
     * @return Crawl4AIResponse with markdown content, or null if failed
     */
    public Crawl4AIResponse crawlSmart(String url) {
        return crawlSmart(url, null);
    }

    /**
     * Crawl a URL using the smart endpoint with optional wait_for selector.
     *
     * @param url The URL to crawl
     * @param waitFor Optional CSS selector to wait for before extraction
     * @return Crawl4AIResponse with markdown content, or null if failed
     */
    public Crawl4AIResponse crawlSmart(String url, String waitFor) {
        if (!enabled) {
            logger.debug("[CrawlClient] Service disabled, skipping");
            return null;
        }

        try {
            String requestBody = buildRequestJson(url, waitFor);

            Request request = new Request.Builder()
                    .url(crawlServiceUrl + "/crawl/smart")
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            logger.info("[CrawlClient] Calling /crawl/smart for: {}", url);

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("[CrawlClient] HTTP error {}: {}", response.code(), url);
                    return null;
                }

                String responseBody = response.body() != null ? response.body().string() : null;
                if (responseBody == null || responseBody.isEmpty()) {
                    logger.warn("[CrawlClient] Empty response for: {}", url);
                    return null;
                }

                Crawl4AIResponse crawlResponse = objectMapper.readValue(responseBody, Crawl4AIResponse.class);

                if (crawlResponse.isSuccess()) {
                    logger.info("[CrawlClient] Success in {}ms, content: {} chars - {}",
                            crawlResponse.getDurationMs(),
                            crawlResponse.getContentLength(),
                            url);
                } else {
                    logger.warn("[CrawlClient] Crawl failed: {} - {}", crawlResponse.getError(), url);
                }

                return crawlResponse;
            }
        } catch (IOException e) {
            logger.error("[CrawlClient] Request failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Crawl a URL using direct Crawl4AI (no Playwright fallback).
     */
    public Crawl4AIResponse crawlDirect(String url) {
        if (!enabled) {
            return null;
        }

        try {
            String requestBody = buildRequestJson(url, null);

            Request request = new Request.Builder()
                    .url(crawlServiceUrl + "/crawl/crawl4ai")
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            logger.info("[CrawlClient] Calling /crawl/crawl4ai for: {}", url);

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }

                String responseBody = response.body() != null ? response.body().string() : null;
                if (responseBody == null) {
                    return null;
                }

                return objectMapper.readValue(responseBody, Crawl4AIResponse.class);
            }
        } catch (IOException e) {
            logger.error("[CrawlClient] Direct crawl failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Check if the Crawl4AI service is healthy.
     */
    public boolean isHealthy() {
        if (!enabled) {
            return false;
        }

        try {
            Request request = new Request.Builder()
                    .url(crawlServiceUrl + "/health")
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (IOException e) {
            logger.warn("[CrawlClient] Health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Fetch raw content (for sitemaps blocked by Cloudflare).
     * Uses Playwright to bypass protection and returns raw HTML/XML.
     *
     * @param url The URL to fetch
     * @return Raw content as String, or null if failed
     */
    public String fetchRawContent(String url) {
        if (!enabled) {
            logger.debug("[CrawlClient] Service disabled, skipping raw fetch");
            return null;
        }

        try {
            String requestBody = buildRequestJson(url, null);

            Request request = new Request.Builder()
                    .url(crawlServiceUrl + "/fetch/raw")
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            logger.info("[CrawlClient] Fetching raw content: {}", url);

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("[CrawlClient] HTTP error {} for raw fetch: {}", response.code(), url);
                    return null;
                }

                String responseBody = response.body() != null ? response.body().string() : null;
                if (responseBody == null || responseBody.isEmpty()) {
                    return null;
                }

                // Parse JSON response
                com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(responseBody);

                boolean success = json.has("success") && json.get("success").asBoolean();
                if (!success) {
                    String error = json.has("error") ? json.get("error").asText() : "Unknown error";
                    logger.warn("[CrawlClient] Raw fetch failed: {} - {}", error, url);
                    return null;
                }

                String content = json.has("content") ? json.get("content").asText() : null;
                int durationMs = json.has("duration_ms") ? json.get("duration_ms").asInt() : 0;

                logger.info("[CrawlClient] Raw fetch success in {}ms, {} chars: {}",
                        durationMs, content != null ? content.length() : 0, url);

                return content;
            }
        } catch (IOException e) {
            logger.error("[CrawlClient] Raw fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String buildRequestJson(String url, String waitFor) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\"url\":\"").append(escapeJson(url)).append("\"");
        if (waitFor != null && !waitFor.isEmpty()) {
            json.append(",\"wait_for\":\"").append(escapeJson(waitFor)).append("\"");
        }
        json.append(",\"wait_timeout\":").append(timeoutMs);
        json.append("}");
        return json.toString();
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
