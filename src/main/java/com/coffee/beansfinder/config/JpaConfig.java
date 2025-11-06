package com.coffee.beansfinder.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

import jakarta.persistence.EntityManagerFactory;

/**
 * JPA Configuration
 * Configures the primary transaction manager for PostgreSQL/JPA
 * Also provides RestTemplate bean for API calls (Perplexity, OpenAI)
 */
@Configuration
public class JpaConfig {

    /**
     * Primary transaction manager for JPA/PostgreSQL
     * Marked as @Primary so it's used by default
     */
    @Primary
    @Bean(name = "transactionManager")
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    /**
     * RestTemplate for making HTTP requests to external APIs
     * Used by PerplexityApiService and OpenAIService
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
