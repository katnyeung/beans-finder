package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.ChatbotResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Semantic cache service using OpenAI embeddings
 * Caches chatbot responses and reuses them for similar questions
 *
 * Example:
 * - User asks "chocolate coffee" → Cache miss → Call Grok → Store response
 * - User asks "chocolatey beans" → Cache hit (similarity 0.96) → Return cached response
 *
 * This is a "LangCache" implementation for chatbot queries
 */
@Service
@Slf4j
public class SemanticCacheService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private OpenAIService openAIService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${chatbot.semantic.cache.similarity.threshold:0.92}")
    private double similarityThreshold;

    @Value("${chatbot.semantic.cache.ttl.hours:1}")
    private int cacheTtlHours;

    @Value("${chatbot.semantic.cache.enabled:true}")
    private boolean cacheEnabled;

    private static final String CACHE_KEY_PREFIX = "semantic:cache:";
    private static final String EMBEDDING_KEY_PREFIX = "semantic:embedding:";

    /**
     * Try to get cached response for a query
     * @param query User's query
     * @return Cached response if similar query found, null otherwise
     */
    public ChatbotResponse getCachedResponse(String query) {
        if (!cacheEnabled) {
            return null;
        }

        try {
            // Step 1: Generate embedding for user query
            float[] queryEmbedding = openAIService.embedText(query);

            // Step 2: Find most similar cached query
            String bestMatchKey = findMostSimilarQuery(queryEmbedding);

            if (bestMatchKey != null) {
                // Step 3: Retrieve cached response
                String cachedJson = redisTemplate.opsForValue().get(bestMatchKey);
                if (cachedJson != null) {
                    CachedResponse cached = objectMapper.readValue(cachedJson, CachedResponse.class);

                    log.info("SEMANTIC CACHE HIT: query='{}', matched='{}', similarity={}",
                            query, cached.originalQuery, cached.similarity);

                    // Update access stats
                    incrementCacheHits();

                    return cached.response;
                }
            }

            // Cache miss
            log.debug("Semantic cache miss for query: {}", query);
            incrementCacheMisses();
            return null;

        } catch (Exception e) {
            log.error("Error checking semantic cache: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Store chatbot response in cache
     * @param query Original user query
     * @param response Chatbot response to cache
     */
    public void cacheResponse(String query, ChatbotResponse response) {
        if (!cacheEnabled) {
            return;
        }

        try {
            // Step 1: Generate embedding for query
            float[] queryEmbedding = openAIService.embedText(query);

            // Step 2: Store embedding (for similarity search)
            String embeddingKey = EMBEDDING_KEY_PREFIX + hashQuery(query);
            String embeddingJson = objectMapper.writeValueAsString(queryEmbedding);
            redisTemplate.opsForValue().set(embeddingKey, embeddingJson, Duration.ofHours(cacheTtlHours));

            // Step 3: Store cached response
            String cacheKey = CACHE_KEY_PREFIX + hashQuery(query);
            CachedResponse cached = new CachedResponse(
                    query,
                    response,
                    queryEmbedding,
                    1.0, // Perfect match for original query
                    LocalDateTime.now()
            );

            String cachedJson = objectMapper.writeValueAsString(cached);
            redisTemplate.opsForValue().set(cacheKey, cachedJson, Duration.ofHours(cacheTtlHours));

            log.info("Cached chatbot response for query: {}", query);

        } catch (Exception e) {
            log.error("Error caching response: {}", e.getMessage(), e);
        }
    }

    /**
     * Find most similar cached query using cosine similarity
     * @param queryEmbedding Embedding of user's query
     * @return Cache key of best match, or null if no match above threshold
     */
    private String findMostSimilarQuery(float[] queryEmbedding) {
        Set<String> embeddingKeys = redisTemplate.keys(EMBEDDING_KEY_PREFIX + "*");
        if (embeddingKeys == null || embeddingKeys.isEmpty()) {
            return null;
        }

        String bestMatchKey = null;
        double bestSimilarity = 0.0;

        for (String embeddingKey : embeddingKeys) {
            try {
                String embeddingJson = redisTemplate.opsForValue().get(embeddingKey);
                if (embeddingJson == null) continue;

                float[] cachedEmbedding = objectMapper.readValue(embeddingJson, float[].class);
                double similarity = cosineSimilarity(queryEmbedding, cachedEmbedding);

                if (similarity > bestSimilarity && similarity >= similarityThreshold) {
                    bestSimilarity = similarity;
                    // Convert embedding key to cache key
                    String hash = embeddingKey.substring(EMBEDDING_KEY_PREFIX.length());
                    bestMatchKey = CACHE_KEY_PREFIX + hash;
                }

            } catch (Exception e) {
                log.warn("Error comparing embedding: {}", e.getMessage());
            }
        }

        return bestMatchKey;
    }

    /**
     * Calculate cosine similarity between two embeddings
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Embeddings must have same length");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Generate hash for query (for cache key)
     */
    private String hashQuery(String query) {
        return String.valueOf(query.toLowerCase().trim().hashCode());
    }

    /**
     * Get cache statistics
     */
    public CacheStats getStats() {
        Set<String> cacheKeys = redisTemplate.keys(CACHE_KEY_PREFIX + "*");
        int cachedQueries = cacheKeys != null ? cacheKeys.size() : 0;

        String hitsKey = "semantic:stats:hits";
        String missesKey = "semantic:stats:misses";

        String hitsStr = redisTemplate.opsForValue().get(hitsKey);
        String missesStr = redisTemplate.opsForValue().get(missesKey);

        long hits = hitsStr != null ? Long.parseLong(hitsStr) : 0;
        long misses = missesStr != null ? Long.parseLong(missesStr) : 0;

        double hitRate = (hits + misses) > 0 ? (double) hits / (hits + misses) : 0.0;

        return new CacheStats(cachedQueries, hits, misses, hitRate, similarityThreshold, cacheTtlHours);
    }

    /**
     * Clear all semantic cache entries
     */
    public void clearCache() {
        Set<String> cacheKeys = redisTemplate.keys(CACHE_KEY_PREFIX + "*");
        Set<String> embeddingKeys = redisTemplate.keys(EMBEDDING_KEY_PREFIX + "*");

        int deleted = 0;
        if (cacheKeys != null) {
            deleted += cacheKeys.size();
            redisTemplate.delete(cacheKeys);
        }
        if (embeddingKeys != null) {
            deleted += embeddingKeys.size();
            redisTemplate.delete(embeddingKeys);
        }

        log.info("Cleared semantic cache: {} keys deleted", deleted);
    }

    private void incrementCacheHits() {
        redisTemplate.opsForValue().increment("semantic:stats:hits");
    }

    private void incrementCacheMisses() {
        redisTemplate.opsForValue().increment("semantic:stats:misses");
    }

    /**
     * Cached response wrapper
     */
    private static class CachedResponse {
        public String originalQuery;
        public ChatbotResponse response;
        public float[] embedding;
        public double similarity;
        public LocalDateTime cachedAt;

        public CachedResponse() {}

        public CachedResponse(String originalQuery, ChatbotResponse response,
                             float[] embedding, double similarity, LocalDateTime cachedAt) {
            this.originalQuery = originalQuery;
            this.response = response;
            this.embedding = embedding;
            this.similarity = similarity;
            this.cachedAt = cachedAt;
        }
    }

    /**
     * Cache statistics
     */
    public record CacheStats(
            int cachedQueries,
            long cacheHits,
            long cacheMisses,
            double hitRate,
            double similarityThreshold,
            int ttlHours
    ) {}
}
