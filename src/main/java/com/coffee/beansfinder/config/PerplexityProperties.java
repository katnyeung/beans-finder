package com.coffee.beansfinder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "coffee.perplexity")
public class PerplexityProperties {
    private String apiKey;
    private String baseUrl = "https://api.perplexity.ai";
    private String model = "llama-3.1-sonar-large-128k-online";
}
