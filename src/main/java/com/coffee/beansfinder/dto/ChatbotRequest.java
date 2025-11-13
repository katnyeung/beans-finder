package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Chatbot request DTO
 * Supports conversational memory and reference product context
 * All state is client-side (no database persistence)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatbotRequest {
    /**
     * User's query (e.g., "Show me something fruitier", "Find coffee similar to this but cheaper")
     */
    private String query;

    /**
     * Conversation history from client (stored in localStorage)
     * Format: [{role: "user"|"assistant", content: "...", products: [...]}]
     */
    private List<Map<String, Object>> messages;

    /**
     * Product IDs already shown in this conversation (from localStorage)
     */
    private List<Long> shownProductIds;

    /**
     * Reference product ID for comparative search
     * (e.g., "Show me something similar to this product")
     */
    private Long referenceProductId;
}
