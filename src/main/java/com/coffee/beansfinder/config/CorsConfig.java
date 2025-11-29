package com.coffee.beansfinder.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * CORS configuration to restrict API access to allowed origins only.
 * Prevents external websites/scripts from calling the LLM-powered chatbot API.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:https://graphee.link,https://www.graphee.link,http://localhost:8080}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.split(",");

        // Restrict chatbot API - only allow from our website
        registry.addMapping("/api/chatbot/**")
                .allowedOrigins(origins)
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);

        // Allow all origins for public read endpoints (brands, products, map, etc.)
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);

        // Static files - allow all
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
