package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.coffee.beansfinder.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for analytics logging and seller redirects.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Analytics", description = "User analytics and redirect endpoints")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final CoffeeProductRepository productRepository;

    /**
     * Log an action from the frontend
     */
    @Operation(summary = "Log user action", description = "Log a user action for analytics")
    @PostMapping("/api/analytics/log")
    public ResponseEntity<Map<String, String>> logAction(
            @RequestBody LogActionRequest request,
            HttpServletRequest httpRequest) {

        String clientIp = getClientIp(httpRequest);

        analyticsService.logAction(
                request.actionType(),
                request.productId(),
                request.brandId(),
                request.metadata(),
                clientIp
        );

        return ResponseEntity.ok(Map.of("status", "logged"));
    }

    /**
     * Redirect to seller website (logs the click and returns redirect info)
     */
    @Operation(summary = "Get seller redirect", description = "Logs seller click and returns redirect URL")
    @GetMapping("/api/go/{productId}")
    public ResponseEntity<?> getSellerRedirect(
            @PathVariable Long productId,
            HttpServletRequest httpRequest) {

        return productRepository.findById(productId)
                .map(product -> {
                    String clientIp = getClientIp(httpRequest);
                    Long brandId = product.getBrand() != null ? product.getBrand().getId() : null;
                    String brandName = product.getBrand() != null ? product.getBrand().getName() : "Seller";
                    String sellerUrl = product.getSellerUrl();

                    if (sellerUrl == null || sellerUrl.isEmpty()) {
                        return ResponseEntity.badRequest().body(Map.of("error", "No seller URL available"));
                    }

                    // Log the seller click
                    analyticsService.logSellerClick(productId, brandId, sellerUrl, clientIp);

                    return ResponseEntity.ok(Map.of(
                            "sellerUrl", sellerUrl,
                            "brandName", brandName,
                            "productName", product.getProductName()
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Extract client IP address from request
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }

    // Request DTO
    public record LogActionRequest(
            String actionType,
            Long productId,
            Long brandId,
            String metadata
    ) {}
}
