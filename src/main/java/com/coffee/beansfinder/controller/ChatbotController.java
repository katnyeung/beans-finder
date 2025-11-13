package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.dto.ChatbotRequest;
import com.coffee.beansfinder.dto.ChatbotResponse;
import com.coffee.beansfinder.service.ChatbotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Chatbot controller for RAG-powered coffee recommendations
 * Integrates Neo4j knowledge graph with Grok LLM
 *
 * IMPORTANT: This is a STATELESS API - all conversation state is client-side (localStorage)
 */
@RestController
@RequestMapping("/api/chatbot")
@Tag(name = "Chatbot", description = "RAG-powered chatbot for coffee recommendations using Neo4j + Grok (stateless, client-side state)")
@RequiredArgsConstructor
@Slf4j
public class ChatbotController {

    private final ChatbotService chatbotService;

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
    public ResponseEntity<ChatbotResponse> query(@RequestBody ChatbotRequest request) {
        log.info("Chatbot query: referenceProductId={}, historyLength={}, query={}",
                request.getReferenceProductId(),
                request.getMessages() != null ? request.getMessages().size() : 0,
                request.getQuery());

        try {
            ChatbotResponse response = chatbotService.processQuery(request);
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
}
