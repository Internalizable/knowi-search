package me.internalizable.knowisearch.ratelimit;

/**
 * Interface for rate limiting implementations.
 * Allows switching between local (Caffeine) and distributed (Redis) rate limiting.
 */
public interface RateLimiter {

    /**
     * Try to consume a token from the rate limit bucket
     * @param key The rate limit key (usually client IP + endpoint)
     * @param limit Maximum tokens per period
     * @param periodSeconds Period in seconds
     * @return true if allowed, false if rate limited
     */
    boolean tryConsume(String key, int limit, int periodSeconds);

    /**
     * Get remaining tokens for a key
     * @param key The rate limit key
     * @param limit Maximum tokens per period
     * @param periodSeconds Period in seconds
     * @return Number of remaining tokens
     */
    long getRemainingTokens(String key, int limit, int periodSeconds);

    /**
     * Reset rate limit for a key (useful for testing or admin overrides)
     * @param key The rate limit key
     */
    void reset(String key);
}

