package com.coffee.beansfinder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Redis-based distributed rate limiter
 * Prevents API abuse by limiting requests per IP address
 */
@Service
@Slf4j
public class RateLimiterService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Value("${chatbot.ratelimit.per.minute:10}")
    private int requestsPerMinute;

    @Value("${chatbot.ratelimit.per.day:200}")
    private int requestsPerDay;

    /**
     * Check if request from this IP is allowed
     * @param clientIp Client IP address
     * @return true if allowed, false if rate limit exceeded
     */
    public boolean allowRequest(String clientIp) {
        String minuteKey = "ratelimit:minute:" + clientIp;
        String dailyKey = "ratelimit:daily:" + clientIp + ":" + LocalDate.now();

        // Check minute limit
        Long minuteCount = redisTemplate.opsForValue().increment(minuteKey);
        if (minuteCount == null) {
            minuteCount = 0L;
        }

        if (minuteCount == 1) {
            redisTemplate.expire(minuteKey, Duration.ofMinutes(1));
        }

        if (minuteCount > requestsPerMinute) {
            log.warn("Rate limit exceeded for IP {}: {} requests in last minute (limit: {})",
                    clientIp, minuteCount, requestsPerMinute);
            return false;
        }

        // Check daily limit
        Long dailyCount = redisTemplate.opsForValue().increment(dailyKey);
        if (dailyCount == null) {
            dailyCount = 0L;
        }

        if (dailyCount == 1) {
            redisTemplate.expire(dailyKey, Duration.ofDays(1));
        }

        if (dailyCount > requestsPerDay) {
            log.warn("Daily rate limit exceeded for IP {}: {} requests today (limit: {})",
                    clientIp, dailyCount, requestsPerDay);
            return false;
        }

        log.debug("Request allowed for IP {}: {}/min, {}/day", clientIp, minuteCount, dailyCount);
        return true;
    }

    /**
     * Get current request count for an IP
     * @param clientIp Client IP address
     * @return Request counts (minute and day)
     */
    public RateLimitStatus getStatus(String clientIp) {
        String minuteKey = "ratelimit:minute:" + clientIp;
        String dailyKey = "ratelimit:daily:" + clientIp + ":" + LocalDate.now();

        String minuteCountStr = redisTemplate.opsForValue().get(minuteKey);
        String dailyCountStr = redisTemplate.opsForValue().get(dailyKey);

        int minuteCount = minuteCountStr != null ? Integer.parseInt(minuteCountStr) : 0;
        int dailyCount = dailyCountStr != null ? Integer.parseInt(dailyCountStr) : 0;

        return new RateLimitStatus(
                minuteCount,
                requestsPerMinute,
                dailyCount,
                requestsPerDay
        );
    }

    /**
     * Rate limit status for an IP
     */
    public record RateLimitStatus(
            int currentMinuteRequests,
            int maxMinuteRequests,
            int currentDailyRequests,
            int maxDailyRequests
    ) {}
}
