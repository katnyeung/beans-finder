package com.coffee.beansfinder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Input validation service for chatbot queries
 * Prevents prompt injection, validates query length, and sanitizes input
 */
@Service
@Slf4j
public class InputValidationService {

    @Value("${chatbot.query.max.length:500}")
    private int maxQueryLength;

    private static final Pattern SUSPICIOUS_PATTERNS = Pattern.compile(
        "(?i)(ignore|bypass|system|prompt|instructions|admin|root|password|token|api.?key|<script|javascript:|on\\w+\\s*=)",
        Pattern.CASE_INSENSITIVE
    );

    // Note: Double quotes allowed since queries go to LLM, not raw SQL
    // Single quotes still blocked as they're rarely needed in natural language
    private static final Pattern SQL_INJECTION_PATTERNS = Pattern.compile(
        "(?i)(union\\s+select|drop\\s+table|insert\\s+into|delete\\s+from|update\\s+.*\\s+set|--|;\\s*$|')",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Validate user query for security and length
     * @param query User's chatbot query
     * @throws IllegalArgumentException if query is invalid
     * @throws SecurityException if query contains suspicious patterns
     */
    public void validateQuery(String query) {
        // Null/empty check
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }

        // Length check
        if (query.length() > maxQueryLength) {
            log.warn("Query too long: {} chars (max: {})", query.length(), maxQueryLength);
            throw new IllegalArgumentException(
                String.format("Query too long (max %d characters)", maxQueryLength)
            );
        }

        // Suspicious pattern check (prompt injection attempt)
        if (SUSPICIOUS_PATTERNS.matcher(query).find()) {
            log.warn("Suspicious query detected: {}", query);
            throw new SecurityException("Query contains suspicious keywords");
        }

        // SQL injection check (should never reach database, but extra safety)
        if (SQL_INJECTION_PATTERNS.matcher(query).find()) {
            log.warn("Potential SQL injection attempt: {}", query);
            throw new SecurityException("Query contains invalid characters");
        }
    }

    /**
     * Sanitize query by trimming whitespace and normalizing spaces
     * @param query Raw query
     * @return Sanitized query
     */
    public String sanitize(String query) {
        if (query == null) {
            return "";
        }

        // Trim whitespace
        query = query.trim();

        // Normalize multiple spaces to single space
        query = query.replaceAll("\\s+", " ");

        return query;
    }
}
