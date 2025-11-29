package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.dto.ChatbotRequest;
import com.coffee.beansfinder.dto.ChatbotResponse;
import com.coffee.beansfinder.service.ChatbotService;
import com.coffee.beansfinder.service.CostTrackingService;
import com.coffee.beansfinder.service.InputValidationService;
import com.coffee.beansfinder.service.RateLimiterService;
import com.coffee.beansfinder.service.SemanticCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Chatbot controller for RAG-powered coffee recommendations
 * Integrates Neo4j knowledge graph with Grok LLM
 *
 * IMPORTANT: This is a STATELESS API - all conversation state is client-side (localStorage)
 *
 * Security Features:
 * - IP-based rate limiting (10/min, 200/day)
 * - Daily cost budget tracking ($10/day default)
 * - Input validation and sanitization
 * - Prompt injection protection
 * - Semantic caching (reuses responses for similar questions)
 */
@RestController
@RequestMapping("/api/chatbot")
@Tag(name = "Chatbot", description = "RAG-powered chatbot for coffee recommendations using Neo4j + Grok (stateless, client-side state)")
@RequiredArgsConstructor
@Slf4j
public class ChatbotController {

    private final ChatbotService chatbotService;
    private final RateLimiterService rateLimiterService;
    private final CostTrackingService costTrackingService;
    private final InputValidationService inputValidationService;
    private final SemanticCacheService semanticCacheService;

    /**
     * Main chatbot endpoint
     * Supports:
     * - Exact product name search
     * - Vague keyword search
     * - Comparative search ("show me something more bitter/fruity/cheaper")
     * - Conversational memory (client sends full history in request)
     *
     * Example requests:
     * - "Show me fruity Ethiopian coffee under £15"
     * - "Find something similar to this product" (with referenceProductId)
     * - "Show me something more bitter" (client tracks reference product)
     * - "Find a cheaper alternative"
     *
     * Client must send: query, messages (conversation history), shownProductIds, referenceProductId
     */
    @PostMapping("/query")
    @Operation(summary = "Ask the chatbot for coffee recommendations",
            description = "RAG-powered chatbot with Neo4j knowledge graph context. Supports comparative search, conversational memory (client-side), and SCA flavor-based filtering.")
    public ResponseEntity<?> query(@RequestBody ChatbotRequest request,
                                     HttpServletRequest httpRequest) {

        String clientIp = getClientIp(httpRequest);

        // Step 1: Check daily cost budget
        if (costTrackingService.isOverBudget()) {
            log.error("DAILY COST BUDGET EXCEEDED: ${}", costTrackingService.getTodayCost());
            return ResponseEntity.status(503).body(Map.of(
                "error", "Service temporarily unavailable",
                "message", "Daily query limit reached. Please try again tomorrow.",
                "cost", costTrackingService.getStats()
            ));
        }

        // Step 2: Check rate limits
        if (!rateLimiterService.allowRequest(clientIp)) {
            log.warn("Rate limit exceeded for IP: {}", clientIp);
            return ResponseEntity.status(429).body(Map.of(
                "error", "Rate limit exceeded",
                "message", "Too many requests. Please slow down.",
                "limits", Map.of(
                    "perMinute", 10,
                    "perDay", 200
                ),
                "retryAfter", 60
            ));
        }

        // Step 3: Validate and sanitize input
        try {
            inputValidationService.validateQuery(request.getQuery());
            String sanitized = inputValidationService.sanitize(request.getQuery());
            request.setQuery(sanitized);
        } catch (IllegalArgumentException | SecurityException e) {
            log.warn("Invalid query from IP {}: {}", clientIp, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage()
            ));
        }

        log.info("Chatbot query from IP {}: referenceProductId={}, historyLength={}, query={}",
                clientIp,
                request.getReferenceProductId(),
                request.getMessages() != null ? request.getMessages().size() : 0,
                request.getQuery());

        try {
            // Step 4: Check semantic cache (only for simple queries without reference product)
            // Skip cache if user has reference product (comparative queries need fresh context)
            ChatbotResponse response = null;
            boolean cacheHit = false;

            if (request.getReferenceProductId() == null) {
                response = semanticCacheService.getCachedResponse(request.getQuery());
                if (response != null) {
                    cacheHit = true;
                    log.info("SEMANTIC CACHE HIT: Returning cached response for query: {}", request.getQuery());
                }
            }

            // Step 5: If cache miss, process query normally
            if (response == null) {
                response = chatbotService.processQuery(request);

                // Cache response for future (only if no reference product)
                if (request.getReferenceProductId() == null) {
                    semanticCacheService.cacheResponse(request.getQuery(), response);
                }

                // Track cost (only on cache miss - cache hits don't call Grok)
                costTrackingService.trackQuery();
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to process chatbot query: {}", e.getMessage(), e);
            return ResponseEntity.ok(ChatbotResponse.builder()
                    .products(null)
                    .explanation("Sorry, I encountered an error. Please try again.")
                    .suggestedActions(null)
                    .error(e.getMessage())
                    .build());
        }
    }

    /**
     * Extract client IP address from request
     * Supports X-Forwarded-For header for proxied requests
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            // Get first IP in chain (original client)
            return ip.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
