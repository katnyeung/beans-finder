package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.dto.ProductTrackingDTO;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.entity.User;
import com.coffee.beansfinder.entity.UserProductTracking;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.coffee.beansfinder.repository.UserProductTrackingRepository;
import com.coffee.beansfinder.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user/tracking")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "User Tracking", description = "Track user's coffee preferences")
public class UserTrackingController {

    private final UserProductTrackingRepository trackingRepository;
    private final CoffeeProductRepository productRepository;
    private final UserService userService;

    @GetMapping
    @Operation(summary = "Get all tracked products", description = "Returns all products the user has tracked")
    public ResponseEntity<List<ProductTrackingDTO>> getAllTracking(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestParam(required = false) String status) {

        User user = getUser(principal);
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        List<UserProductTracking> trackings;
        if (status != null && !status.isEmpty()) {
            UserProductTracking.TrackingStatus trackingStatus =
                    UserProductTracking.TrackingStatus.valueOf(status.toUpperCase());
            trackings = trackingRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(user.getId(), trackingStatus);
        } else {
            trackings = trackingRepository.findByUserIdOrderByUpdatedAtDesc(user.getId());
        }

        List<ProductTrackingDTO> dtos = trackings.stream()
                .map(ProductTrackingDTO::fromEntity)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Get tracking for a product", description = "Returns user's tracking status for a specific product")
    public ResponseEntity<ProductTrackingDTO> getTracking(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable Long productId) {

        User user = getUser(principal);
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        Optional<UserProductTracking> tracking =
                trackingRepository.findByUserIdAndProductId(user.getId(), productId);

        return tracking.map(t -> ResponseEntity.ok(ProductTrackingDTO.fromEntity(t)))
                .orElse(ResponseEntity.ok(null));
    }

    @PostMapping("/{productId}")
    @Transactional
    @Operation(summary = "Add or update tracking", description = "Set tracking status and optional rating for a product")
    public ResponseEntity<ProductTrackingDTO> setTracking(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable Long productId,
            @RequestBody ProductTrackingDTO.Request request) {

        User user = getUser(principal);
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        Optional<CoffeeProduct> productOpt = productRepository.findById(productId);
        if (productOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        CoffeeProduct product = productOpt.get();

        // Find existing or create new
        UserProductTracking tracking = trackingRepository
                .findByUserIdAndProductId(user.getId(), productId)
                .orElse(UserProductTracking.builder()
                        .user(user)
                        .product(product)
                        .build());

        // Update fields
        tracking.setStatus(request.getTrackingStatus());
        if (request.getRating() != null && request.getRating() >= 1 && request.getRating() <= 5) {
            tracking.setRating(request.getRating().shortValue());
        }
        if (request.getNotes() != null) {
            tracking.setNotes(request.getNotes());
        }

        tracking = trackingRepository.save(tracking);
        log.info("User {} tracked product {} as {}", user.getEmail(), productId, request.getStatus());

        return ResponseEntity.ok(ProductTrackingDTO.fromEntity(tracking));
    }

    @PatchMapping("/{productId}/include-in-chat")
    @Transactional
    @Operation(summary = "Toggle include in chat", description = "Toggle whether this product is included in chatbot suggestions")
    public ResponseEntity<ProductTrackingDTO> toggleIncludeInChat(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable Long productId,
            @RequestBody IncludeInChatRequest request) {

        User user = getUser(principal);
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        Optional<UserProductTracking> trackingOpt =
                trackingRepository.findByUserIdAndProductId(user.getId(), productId);

        if (trackingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        UserProductTracking tracking = trackingOpt.get();
        tracking.setIncludedInChat(request.isIncludedInChat());
        tracking = trackingRepository.save(tracking);
        log.info("User {} set includedInChat={} for product {}", user.getEmail(), request.isIncludedInChat(), productId);

        return ResponseEntity.ok(ProductTrackingDTO.fromEntity(tracking));
    }

    @DeleteMapping("/{productId}")
    @Transactional
    @Operation(summary = "Remove tracking", description = "Remove tracking status for a product")
    public ResponseEntity<Void> removeTracking(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable Long productId) {

        User user = getUser(principal);
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        trackingRepository.deleteByUserIdAndProductId(user.getId(), productId);
        log.info("User {} removed tracking for product {}", user.getEmail(), productId);

        return ResponseEntity.ok().build();
    }

    private User getUser(OAuth2User principal) {
        if (principal == null) return null;
        String googleId = principal.getAttribute("sub");
        return userService.findByGoogleId(googleId).orElse(null);
    }

    /**
     * Request body for toggling include-in-chat flag
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IncludeInChatRequest {
        private boolean includedInChat;
    }
}
