package com.coffee.beansfinder.service;

import com.coffee.beansfinder.entity.InstagramCredentials;
import com.coffee.beansfinder.repository.InstagramCredentialsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Instagram Platform API OAuth Service
 * Uses Instagram Direct Login (not Facebook)
 * Works with both Business and Creator accounts
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InstagramOAuthService {

    private final InstagramCredentialsRepository credentialsRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${instagram.app.id:}")
    private String appId;

    @Value("${instagram.app.secret:}")
    private String appSecret;

    @Value("${instagram.oauth.redirect.uri:}")
    private String redirectUri;

    // Fallback to env vars if no database credentials
    @Value("${instagram.user.id:}")
    private String envUserId;

    @Value("${instagram.access.token:}")
    private String envAccessToken;

    private static final String INSTAGRAM_AUTH_URL = "https://api.instagram.com/oauth/authorize";
    private static final String INSTAGRAM_TOKEN_URL = "https://api.instagram.com/oauth/access_token";
    private static final String INSTAGRAM_GRAPH_URL = "https://graph.instagram.com";

    /**
     * Generate the OAuth authorization URL for Instagram login
     */
    public String getAuthorizationUrl() {
        // Instagram Platform API scopes for content publishing
        String scopes = "instagram_business_basic,instagram_business_content_publish";

        return String.format(
                "%s?client_id=%s&redirect_uri=%s&scope=%s&response_type=code",
                INSTAGRAM_AUTH_URL,
                appId,
                redirectUri,
                scopes
        );
    }

    /**
     * Exchange authorization code for access token
     */
    public InstagramCredentials exchangeCodeForToken(String code) {
        log.info("Exchanging authorization code for access token...");

        try {
            // Step 1: Get short-lived token
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("client_id", appId);
            params.add("client_secret", appSecret);
            params.add("grant_type", "authorization_code");
            params.add("redirect_uri", redirectUri);
            params.add("code", code);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(INSTAGRAM_TOKEN_URL, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Failed to exchange code: " + response.getBody());
            }

            JsonNode tokenJson = objectMapper.readTree(response.getBody());
            String shortLivedToken = tokenJson.path("access_token").asText();
            String userId = tokenJson.path("user_id").asText();

            log.info("Got short-lived token for user: {}", userId);

            // Step 2: Exchange for long-lived token (60 days)
            String longLivedToken = exchangeForLongLivedToken(shortLivedToken);

            // Step 3: Get user profile
            String username = getUserProfile(longLivedToken, userId);

            // Step 4: Save credentials
            InstagramCredentials credentials = credentialsRepository.findByInstagramUserId(userId)
                    .orElse(InstagramCredentials.builder()
                            .instagramUserId(userId)
                            .build());

            credentials.setAccessToken(longLivedToken);
            credentials.setUsername(username);
            credentials.setTokenExpiresAt(LocalDateTime.now().plusDays(60));
            credentials.setUpdatedAt(LocalDateTime.now());

            credentials = credentialsRepository.save(credentials);
            log.info("Saved Instagram credentials for user: {} (@{})", userId, username);

            return credentials;

        } catch (Exception e) {
            log.error("Failed to exchange code for token: {}", e.getMessage(), e);
            throw new RuntimeException("OAuth token exchange failed: " + e.getMessage());
        }
    }

    /**
     * Exchange short-lived token for long-lived token (60 days)
     */
    private String exchangeForLongLivedToken(String shortLivedToken) {
        String url = String.format(
                "%s/access_token?grant_type=ig_exchange_token&client_secret=%s&access_token=%s",
                INSTAGRAM_GRAPH_URL,
                appSecret,
                shortLivedToken
        );

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode json = objectMapper.readTree(response.getBody());
            String longLivedToken = json.path("access_token").asText();
            log.info("Exchanged for long-lived token (expires in {} seconds)", json.path("expires_in").asLong());
            return longLivedToken;
        } catch (Exception e) {
            log.warn("Failed to get long-lived token, using short-lived: {}", e.getMessage());
            return shortLivedToken;
        }
    }

    /**
     * Refresh a long-lived token (must be done before expiry)
     */
    public InstagramCredentials refreshToken(String instagramUserId) {
        InstagramCredentials credentials = credentialsRepository.findByInstagramUserId(instagramUserId)
                .orElseThrow(() -> new RuntimeException("No credentials found for user: " + instagramUserId));

        String url = String.format(
                "%s/refresh_access_token?grant_type=ig_refresh_token&access_token=%s",
                INSTAGRAM_GRAPH_URL,
                credentials.getAccessToken()
        );

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode json = objectMapper.readTree(response.getBody());

            credentials.setAccessToken(json.path("access_token").asText());
            credentials.setTokenExpiresAt(LocalDateTime.now().plusDays(60));
            credentials.setUpdatedAt(LocalDateTime.now());

            credentials = credentialsRepository.save(credentials);
            log.info("Refreshed token for user: {}", instagramUserId);

            return credentials;
        } catch (Exception e) {
            log.error("Failed to refresh token: {}", e.getMessage());
            throw new RuntimeException("Token refresh failed: " + e.getMessage());
        }
    }

    /**
     * Get user profile information
     */
    private String getUserProfile(String accessToken, String userId) {
        String url = String.format(
                "%s/%s?fields=username&access_token=%s",
                INSTAGRAM_GRAPH_URL,
                userId,
                accessToken
        );

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode json = objectMapper.readTree(response.getBody());
            return json.path("username").asText();
        } catch (Exception e) {
            log.warn("Failed to get username: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the current stored credentials (checks DB first, then env vars)
     */
    public Optional<InstagramCredentials> getCurrentCredentials() {
        // First check database
        Optional<InstagramCredentials> dbCreds = credentialsRepository.findFirstByOrderByCreatedAtDesc();
        if (dbCreds.isPresent()) {
            log.info("Using Instagram credentials from DATABASE (user: {})", dbCreds.get().getInstagramUserId());
            return dbCreds;
        }

        // Fallback to environment variables
        if (envUserId != null && !envUserId.isEmpty() && envAccessToken != null && !envAccessToken.isEmpty()) {
            log.info("Using Instagram credentials from ENVIRONMENT VARIABLES (user: {}, token length: {})",
                    envUserId, envAccessToken.length());
            return Optional.of(InstagramCredentials.builder()
                    .instagramUserId(envUserId)
                    .accessToken(envAccessToken)
                    .tokenExpiresAt(LocalDateTime.now().plusDays(60)) // Assume valid
                    .build());
        }

        log.warn("No Instagram credentials found in DB or environment variables");
        return Optional.empty();
    }

    /**
     * Check if we have valid credentials
     */
    public boolean hasValidCredentials() {
        return getCurrentCredentials()
                .map(c -> !c.isTokenExpired())
                .orElse(false);
    }

    /**
     * Manually save credentials (for tokens obtained from Meta dashboard)
     */
    public InstagramCredentials saveCredentialsManually(String userId, String accessToken) {
        log.info("Manually saving Instagram credentials for user: {}", userId);

        // Clean token - remove any non-alphanumeric characters from start/end
        // Common issues: trailing >, quotes, spaces from copy-paste
        accessToken = accessToken.trim();
        accessToken = accessToken.replaceAll("^[^a-zA-Z0-9]+", ""); // Remove leading non-alphanum
        accessToken = accessToken.replaceAll("[^a-zA-Z0-9]+$", ""); // Remove trailing non-alphanum
        log.info("Cleaned token length: {}", accessToken.length());

        // Try to get username from Instagram API
        String username = getUserProfile(accessToken, userId);

        InstagramCredentials credentials = credentialsRepository.findByInstagramUserId(userId)
                .orElse(InstagramCredentials.builder()
                        .instagramUserId(userId)
                        .createdAt(LocalDateTime.now())
                        .build());

        credentials.setAccessToken(accessToken);
        credentials.setUsername(username);
        credentials.setTokenExpiresAt(LocalDateTime.now().plusDays(60)); // Assume long-lived token
        credentials.setUpdatedAt(LocalDateTime.now());

        credentials = credentialsRepository.save(credentials);
        log.info("Saved Instagram credentials for user: {} (@{})", userId, username);

        return credentials;
    }

    /**
     * Delete stored credentials (disconnect Instagram)
     */
    public void deleteCredentials(String userId) {
        credentialsRepository.findByInstagramUserId(userId)
                .ifPresent(credentialsRepository::delete);
        log.info("Deleted Instagram credentials for user: {}", userId);
    }
}
