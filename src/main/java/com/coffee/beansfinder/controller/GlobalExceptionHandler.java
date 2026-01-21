package com.coffee.beansfinder.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLTransientConnectionException;

/**
 * Global exception handler for graceful degradation during database unavailability.
 *
 * Handles Neon serverless cold start scenarios by returning 503 Service Unavailable
 * instead of letting the request hang or crash. This allows:
 * - Frontend to show friendly "service temporarily unavailable" message
 * - Load balancer health checks to route traffic appropriately
 * - Monitoring systems to track availability without false alarms
 */
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handle database connection failures (Neon cold start, network issues, etc.)
     * Returns 503 Service Unavailable with retry hint.
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<String> handleDatabaseUnavailable(DataAccessResourceFailureException ex) {
        log.warn("Database unavailable (likely Neon cold start): {}", ex.getMessage());

        // Return plain text to avoid HttpMediaTypeNotAcceptableException with non-JSON clients
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "10")
                .contentType(MediaType.TEXT_PLAIN)
                .body("Service temporarily unavailable. Database is waking up. Please retry in 10 seconds.");
    }

    /**
     * Handle SQL transient connection exceptions (connection pool timeout, etc.)
     */
    @ExceptionHandler(SQLTransientConnectionException.class)
    public ResponseEntity<String> handleTransientConnection(SQLTransientConnectionException ex) {
        log.warn("Transient database connection error: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "10")
                .contentType(MediaType.TEXT_PLAIN)
                .body("Service temporarily unavailable. Database connection timeout. Please retry in 10 seconds.");
    }

    /**
     * Handle missing static resources (404 errors).
     * Returns 404 Not Found without logging to avoid log spam from bot scans.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> handleNoResourceFound(NoResourceFoundException ex) {
        // Don't log - these are typically bot scans for login.html, wp-admin, etc.
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Not Found");
    }

    /**
     * Handle HTTP method not supported (bot scanners hitting endpoints with wrong methods).
     * Returns 405 Method Not Allowed silently without warning logs to reduce noise.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        // Don't log - these are typically bot scans
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Method Not Allowed");
    }

    /**
     * Handle media type not acceptable (client doesn't accept JSON).
     * Returns plain text to avoid recursive exception when client rejects JSON.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<String> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        // Don't log - these are typically bots with weird Accept headers
        return ResponseEntity
                .status(HttpStatus.NOT_ACCEPTABLE)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Not Acceptable");
    }

    /**
     * Handle HikariPool connection timeout wrapped in various exceptions.
     * Checks if root cause is connection-related.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGenericException(Exception ex) {
        // Check if this is a database connection issue wrapped in another exception
        Throwable cause = ex;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && (
                    message.contains("HikariPool") ||
                    message.contains("Connection is not available") ||
                    message.contains("Unable to acquire JDBC Connection") ||
                    message.contains("UnknownHostException"))) {

                log.warn("Database connection issue detected in nested exception: {}", message);
                return ResponseEntity
                        .status(HttpStatus.SERVICE_UNAVAILABLE)
                        .header("Retry-After", "10")
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Service temporarily unavailable. Database is waking up. Please retry in 10 seconds.");
            }
            cause = cause.getCause();
        }

        // Log unexpected errors and return 500
        // Use plain text to avoid HttpMediaTypeNotAcceptableException from handler itself
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Internal Server Error");
    }
}
