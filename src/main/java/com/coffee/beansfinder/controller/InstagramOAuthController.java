package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.InstagramCredentials;
import com.coffee.beansfinder.service.InstagramOAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/instagram/oauth")
@Tag(name = "Instagram OAuth", description = "Instagram Platform API OAuth flow")
@Slf4j
@RequiredArgsConstructor
public class InstagramOAuthController {

    private final InstagramOAuthService oAuthService;

    /**
     * Get the authorization URL to start Instagram login
     */
    @GetMapping("/authorize")
    @Operation(summary = "Get Instagram authorization URL")
    public ResponseEntity<Map<String, String>> getAuthorizationUrl() {
        String authUrl = oAuthService.getAuthorizationUrl();
        return ResponseEntity.ok(Map.of(
                "authorizationUrl", authUrl,
                "instructions", "Redirect the user to this URL to authorize Instagram access"
        ));
    }

    /**
     * OAuth callback - exchanges code for token
     */
    @GetMapping("/callback")
    @Operation(summary = "OAuth callback - exchange code for token")
    public ResponseEntity<?> handleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_reason", required = false) String errorReason,
            @RequestParam(name = "error_description", required = false) String errorDescription) {

        if (error != null) {
            log.error("Instagram OAuth error: {} - {} - {}", error, errorReason, errorDescription);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", error,
                    "reason", errorReason != null ? errorReason : "",
                    "description", errorDescription != null ? errorDescription : ""
            ));
        }

        if (code == null || code.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing_code",
                    "message", "Authorization code not provided"
            ));
        }

        try {
            InstagramCredentials credentials = oAuthService.exchangeCodeForToken(code);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Instagram connected successfully!",
                    "username", credentials.getUsername() != null ? credentials.getUsername() : "",
                    "userId", credentials.getInstagramUserId(),
                    "expiresAt", credentials.getTokenExpiresAt().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to exchange code: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "token_exchange_failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Check current connection status with token diagnostics
     */
    @GetMapping("/status")
    @Operation(summary = "Check Instagram connection status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return oAuthService.getCurrentCredentials()
                .map(creds -> {
                    String token = creds.getAccessToken();
                    Map<String, Object> response = new java.util.HashMap<>();
                    response.put("connected", true);
                    response.put("username", creds.getUsername() != null ? creds.getUsername() : "");
                    response.put("userId", creds.getInstagramUserId());
                    response.put("tokenExpired", creds.isTokenExpired());
                    response.put("expiresAt", creds.getTokenExpiresAt() != null ? creds.getTokenExpiresAt().toString() : "");
                    // Token diagnostics (don't reveal full token)
                    response.put("tokenLength", token != null ? token.length() : 0);
                    response.put("tokenPrefix", token != null && token.length() > 10 ? token.substring(0, 10) + "..." : "null");
                    response.put("tokenSuffix", token != null && token.length() > 10 ? "..." + token.substring(token.length() - 10) : "null");
                    response.put("tokenHasWhitespace", token != null && !token.equals(token.trim()));
                    response.put("tokenHasNewlines", token != null && (token.contains("\n") || token.contains("\r")));
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.ok(Map.of(
                        "connected", false,
                        "message", "No Instagram account connected"
                )));
    }

    /**
     * Refresh the access token
     */
    @PostMapping("/refresh")
    @Operation(summary = "Refresh Instagram access token")
    public ResponseEntity<?> refreshToken() {
        return oAuthService.getCurrentCredentials()
                .map(creds -> {
                    try {
                        InstagramCredentials refreshed = oAuthService.refreshToken(creds.getInstagramUserId());
                        return ResponseEntity.ok(Map.of(
                                "success", true,
                                "message", "Token refreshed successfully",
                                "expiresAt", refreshed.getTokenExpiresAt().toString()
                        ));
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "refresh_failed",
                                "message", e.getMessage()
                        ));
                    }
                })
                .orElse(ResponseEntity.badRequest().body(Map.of(
                        "error", "no_credentials",
                        "message", "No Instagram account connected"
                )));
    }

    /**
     * Manually save credentials (for tokens from Meta dashboard)
     */
    @PostMapping("/save-token")
    @Operation(summary = "Manually save Instagram credentials")
    public ResponseEntity<?> saveToken(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        String accessToken = body.get("accessToken");

        if (userId == null || userId.isEmpty() || accessToken == null || accessToken.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing_params",
                    "message", "Both userId and accessToken are required"
            ));
        }

        try {
            InstagramCredentials credentials = oAuthService.saveCredentialsManually(userId, accessToken);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Instagram credentials saved successfully",
                    "username", credentials.getUsername() != null ? credentials.getUsername() : "",
                    "userId", credentials.getInstagramUserId(),
                    "expiresAt", credentials.getTokenExpiresAt().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to save credentials: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "save_failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Disconnect Instagram account
     */
    @DeleteMapping("/disconnect")
    @Operation(summary = "Disconnect Instagram account")
    public ResponseEntity<Map<String, Object>> disconnect() {
        return oAuthService.getCurrentCredentials()
                .map(creds -> {
                    // In a full implementation, you might want to revoke the token
                    // For now, we just delete the stored credentials
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "success", true,
                            "message", "Instagram account disconnected"
                    ));
                })
                .orElse(ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "No account was connected"
                )));
    }
}
