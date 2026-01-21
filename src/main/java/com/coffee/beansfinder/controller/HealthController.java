package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.service.DatabaseHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health check endpoint with circuit breaker status
 */
@RestController
@RequestMapping("/api/health")
@Tag(name = "Health", description = "System health checks")
@RequiredArgsConstructor
public class HealthController {

    private final DatabaseHealthService databaseHealthService;

    @GetMapping
    @Operation(summary = "Get system health status")
    public ResponseEntity<Map<String, Object>> getHealth() {
        boolean dbAvailable = databaseHealthService.isDatabaseAvailable();

        return ResponseEntity.ok(Map.of(
            "status", dbAvailable ? "UP" : "DEGRADED",
            "database", Map.of(
                "available", dbAvailable,
                "cachedState", databaseHealthService.getCachedAvailability()
            ),
            "timestamp", System.currentTimeMillis()
        ));
    }

    @GetMapping("/db")
    @Operation(summary = "Check database connectivity")
    public ResponseEntity<Map<String, Object>> getDatabaseHealth() {
        boolean dbAvailable = databaseHealthService.isDatabaseAvailable();

        if (dbAvailable) {
            return ResponseEntity.ok(Map.of(
                "status", "UP",
                "message", "Database connection successful"
            ));
        } else {
            return ResponseEntity.status(503).body(Map.of(
                "status", "DOWN",
                "message", "Database unavailable (circuit breaker may be open)"
            ));
        }
    }
}
