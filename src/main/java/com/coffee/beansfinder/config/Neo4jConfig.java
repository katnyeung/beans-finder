package com.coffee.beansfinder.config;

import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Neo4j Configuration
 * Enables Neo4j repositories and transaction management for Spring Data Neo4j 7.x
 * Configures separate transaction manager for Neo4j to avoid conflicts with JPA
 */
@Configuration
@EnableNeo4jRepositories(
    basePackages = "com.coffee.beansfinder.graph.repository",
    transactionManagerRef = "neo4jTransactionManager"
)
@EnableTransactionManagement
public class Neo4jConfig {

    /**
     * Neo4j-specific transaction manager
     * Separate from JPA transaction manager to avoid conflicts
     */
    @Bean(name = "neo4jTransactionManager")
    public PlatformTransactionManager neo4jTransactionManager(Driver driver) {
        return new Neo4jTransactionManager(driver);
    }
}
