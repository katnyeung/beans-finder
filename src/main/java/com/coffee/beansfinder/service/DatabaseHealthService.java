package com.coffee.beansfinder.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Database health check service with circuit breaker protection.
 * Prevents restart loops when Neon serverless DB is in cold stop.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DatabaseHealthService {

    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean databaseAvailable = new AtomicBoolean(false);

    /**
     * Check if database is available with circuit breaker protection.
     * If DB fails repeatedly, circuit opens and returns false without trying.
     */
    @CircuitBreaker(name = "database", fallbackMethod = "healthCheckFallback")
    @Retry(name = "database")
    public boolean isDatabaseAvailable() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            databaseAvailable.set(true);
            return true;
        } catch (Exception e) {
            databaseAvailable.set(false);
            log.warn("Database health check failed: {}", e.getMessage());
            throw e; // Let circuit breaker handle it
        }
    }

    /**
     * Fallback when circuit is open - returns cached state without hitting DB
     */
    public boolean healthCheckFallback(Exception e) {
        log.warn("Database circuit breaker open, returning cached state. Error: {}", e.getMessage());
        return databaseAvailable.get();
    }

    /**
     * Get current cached database availability state (no DB call)
     */
    public boolean getCachedAvailability() {
        return databaseAvailable.get();
    }

    /**
     * Execute a query with circuit breaker protection
     */
    @CircuitBreaker(name = "database", fallbackMethod = "queryFallback")
    @Retry(name = "database")
    public <T> T executeQuery(QueryOperation<T> operation) {
        return operation.execute();
    }

    public <T> T queryFallback(QueryOperation<T> operation, Exception e) {
        log.error("Database query failed, circuit breaker active: {}", e.getMessage());
        return null;
    }

    @FunctionalInterface
    public interface QueryOperation<T> {
        T execute();
    }
}
