package com.coffee.beansfinder.service;

import com.coffee.beansfinder.entity.UserAnalyticsLog;
import com.coffee.beansfinder.repository.UserAnalyticsLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Service for logging user analytics.
 * Tracks product views, seller clicks, and chatbot interactions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    private final UserAnalyticsLogRepository analyticsRepository;
    private final ObjectMapper objectMapper;

    /**
     * Log a product view
     */
    @Async
    public void logProductView(Long productId, Long brandId, String ipAddress) {
        try {
            UserAnalyticsLog logEntry = UserAnalyticsLog.builder()
                    .actionType(UserAnalyticsLog.ACTION_PRODUCT_VIEW)
                    .productId(productId)
                    .brandId(brandId)
                    .ipHash(hashIp(ipAddress))
                    .createdAt(LocalDateTime.now())
                    .build();

            analyticsRepository.save(logEntry);
            log.debug("Logged product_view: productId={}, brandId={}", productId, brandId);
        } catch (Exception e) {
            log.error("Failed to log product view: {}", e.getMessage());
        }
    }

    /**
     * Log a seller click (user going to seller website)
     */
    @Async
    public void logSellerClick(Long productId, Long brandId, String sellerUrl, String ipAddress) {
        try {
            Map<String, Object> metadata = Map.of("sellerUrl", sellerUrl);

            UserAnalyticsLog logEntry = UserAnalyticsLog.builder()
                    .actionType(UserAnalyticsLog.ACTION_SELLER_CLICK)
                    .productId(productId)
                    .brandId(brandId)
                    .ipHash(hashIp(ipAddress))
                    .metadata(objectMapper.writeValueAsString(metadata))
                    .createdAt(LocalDateTime.now())
                    .build();

            analyticsRepository.save(logEntry);
            log.debug("Logged seller_click: productId={}, brandId={}", productId, brandId);
        } catch (Exception e) {
            log.error("Failed to log seller click: {}", e.getMessage());
        }
    }

    /**
     * Log a chatbot question
     */
    @Async
    public void logChatQuestion(String question, String ipAddress) {
        try {
            Map<String, Object> metadata = Map.of("question", question);

            UserAnalyticsLog logEntry = UserAnalyticsLog.builder()
                    .actionType(UserAnalyticsLog.ACTION_CHAT_QUESTION)
                    .ipHash(hashIp(ipAddress))
                    .metadata(objectMapper.writeValueAsString(metadata))
                    .createdAt(LocalDateTime.now())
                    .build();

            analyticsRepository.save(logEntry);
            log.debug("Logged chat_question: {}", question.substring(0, Math.min(50, question.length())));
        } catch (Exception e) {
            log.error("Failed to log chat question: {}", e.getMessage());
        }
    }

    /**
     * Log a chatbot answer with recommended products
     */
    @Async
    public void logChatAnswer(List<Long> productIds, List<Long> brandIds, String ipAddress) {
        try {
            // Log one entry per brand that appeared in recommendations
            for (int i = 0; i < brandIds.size(); i++) {
                Long brandId = brandIds.get(i);
                Long productId = i < productIds.size() ? productIds.get(i) : null;

                Map<String, Object> metadata = Map.of(
                        "totalRecommendations", productIds.size(),
                        "allProductIds", productIds
                );

                UserAnalyticsLog logEntry = UserAnalyticsLog.builder()
                        .actionType(UserAnalyticsLog.ACTION_CHAT_ANSWER)
                        .productId(productId)
                        .brandId(brandId)
                        .ipHash(hashIp(ipAddress))
                        .metadata(objectMapper.writeValueAsString(metadata))
                        .createdAt(LocalDateTime.now())
                        .build();

                analyticsRepository.save(logEntry);
            }

            log.debug("Logged chat_answer: {} products from {} brands", productIds.size(), brandIds.size());
        } catch (Exception e) {
            log.error("Failed to log chat answer: {}", e.getMessage());
        }
    }

    /**
     * Generic log method for frontend calls
     */
    public void logAction(String actionType, Long productId, Long brandId, String metadata, String ipAddress) {
        try {
            UserAnalyticsLog logEntry = UserAnalyticsLog.builder()
                    .actionType(actionType)
                    .productId(productId)
                    .brandId(brandId)
                    .ipHash(hashIp(ipAddress))
                    .metadata(metadata)
                    .createdAt(LocalDateTime.now())
                    .build();

            analyticsRepository.save(logEntry);
            log.debug("Logged action: type={}, productId={}, brandId={}", actionType, productId, brandId);
        } catch (Exception e) {
            log.error("Failed to log action: {}", e.getMessage());
        }
    }

    /**
     * Hash IP address for privacy
     */
    private String hashIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(ipAddress.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16); // First 16 chars
        } catch (Exception e) {
            return null;
        }
    }
}
