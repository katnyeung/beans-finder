package com.coffee.beansfinder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Service for generating content hashes to detect page changes.
 * Uses SHA-256 for deterministic, collision-resistant hashing.
 */
@Service
@Slf4j
public class ContentHashService {

    private static final String HASH_ALGORITHM = "SHA-256";

    /**
     * Generate SHA-256 hash from content string.
     * Returns 64-character hex string.
     *
     * @param content The text content to hash (typically Playwright-extracted text)
     * @return 64-char hex hash, or null if content is null/empty
     */
    public String generateHash(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 algorithm not available", e);
            throw new RuntimeException("Failed to generate content hash", e);
        }
    }

    /**
     * Check if content has changed by comparing hashes.
     *
     * @param newHash Hash of newly extracted content
     * @param existingHash Hash stored in database
     * @return true if content changed (hashes differ), false if unchanged
     */
    public boolean hasContentChanged(String newHash, String existingHash) {
        if (newHash == null && existingHash == null) {
            return false; // Both null = no change
        }
        if (newHash == null || existingHash == null) {
            return true; // One is null = change
        }
        return !newHash.equals(existingHash);
    }

    /**
     * Convert byte array to hex string.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
