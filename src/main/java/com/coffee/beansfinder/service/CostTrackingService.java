package com.coffee.beansfinder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Cost tracking service to monitor and limit daily API costs
 * Prevents cost explosion from spam or abuse
 */
@Service
@Slf4j
public class CostTrackingService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Value("${chatbot.cost.daily.limit:10.00}")
    private double dailyCostLimit;

    // Cost per chatbot query (2 Grok API calls)
    private static final double GROK_COST_PER_QUERY = 0.0005;

    /**
     * Track a chatbot query cost
     */
    public void trackQuery() {
        String key = getCostKey();

        // Increment cost atomically
        redisTemplate.opsForValue().increment(key, GROK_COST_PER_QUERY);

        // Set expiry on first increment
        if (redisTemplate.getExpire(key) == null || redisTemplate.getExpire(key) < 0) {
            redisTemplate.expire(key, Duration.ofDays(1));
        }

        double currentCost = getTodayCost();
        log.debug("Daily cost updated: ${} (limit: ${})", currentCost, dailyCostLimit);

        // Alert if approaching limit
        if (currentCost >= dailyCostLimit * 0.9) {
            log.warn("COST ALERT: Daily cost at ${} (90% of ${} limit)", currentCost, dailyCostLimit);
        }
    }

    /**
     * Check if daily cost budget has been exceeded
     * @return true if over budget, false otherwise
     */
    public boolean isOverBudget() {
        double currentCost = getTodayCost();
        return currentCost >= dailyCostLimit;
    }

    /**
     * Get today's total cost
     * @return Cost in dollars
     */
    public double getTodayCost() {
        String key = getCostKey();
        String costStr = redisTemplate.opsForValue().get(key);

        if (costStr == null) {
            return 0.0;
        }

        try {
            return Double.parseDouble(costStr);
        } catch (NumberFormatException e) {
            log.error("Invalid cost value in Redis: {}", costStr);
            return 0.0;
        }
    }

    /**
     * Get today's query count (estimated from cost)
     * @return Number of queries
     */
    public int getTodayQueryCount() {
        return (int) (getTodayCost() / GROK_COST_PER_QUERY);
    }

    /**
     * Get cost statistics
     * @return Cost stats
     */
    public CostStats getStats() {
        double currentCost = getTodayCost();
        int queryCount = getTodayQueryCount();
        double remainingBudget = Math.max(0, dailyCostLimit - currentCost);
        int remainingQueries = (int) (remainingBudget / GROK_COST_PER_QUERY);

        return new CostStats(
                currentCost,
                dailyCostLimit,
                queryCount,
                remainingBudget,
                remainingQueries
        );
    }

    /**
     * Reset today's cost (admin function)
     */
    public void resetDailyCost() {
        String key = getCostKey();
        redisTemplate.delete(key);
        log.info("Daily cost reset for {}", LocalDate.now());
    }

    private String getCostKey() {
        return "cost:daily:" + LocalDate.now();
    }

    /**
     * Cost statistics record
     */
    public record CostStats(
            double currentCost,
            double dailyLimit,
            int queryCount,
            double remainingBudget,
            int remainingQueries
    ) {}
}
