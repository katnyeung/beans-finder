package com.coffee.beansfinder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "coffee.crawler")
public class CrawlerProperties {
    private boolean enabled = true;
    private int delaySeconds = 2;
    private int retryAttempts = 3;
    private int updateIntervalDays = 14;
    private String cronSchedule = "0 0 2 * * ?";
}
