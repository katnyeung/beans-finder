package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Chatbot response DTO
 * Contains recommended products + natural language explanation
 * No conversationId - all state is client-side (localStorage)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatbotResponse {
    /**
     * List of recommended products
     */
    private List<ProductRecommendation> products;

    /**
     * Natural language explanation from Grok
     */
    private String explanation;

    /**
     * Suggested next actions (Grok-generated quick action buttons)
     */
    private List<Map<String, String>> suggestedActions;

    /**
     * Error message (if any)
     */
    private String error;
}
