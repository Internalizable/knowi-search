package me.internalizable.knowisearch.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Distributed rate limiter using Redis with Lua scripting for atomic operations.
 * Works correctly across multiple servers/load balancers.
 *
 * Uses the Token Bucket algorithm with Redis:
 * - Atomic increment + expiry in a single Lua script
 * - No race conditions between servers
 * - Automatic cleanup via Redis TTL
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RedisRateLimiter.class);

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;

    // thread safe Lua script for atomic rate limiting
    private static final String RATE_LIMIT_LUA = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local period = tonumber(ARGV[2])
            
            local current = redis.call('GET', key)
            
            if current == false then
                redis.call('SET', key, 1, 'EX', period)
                return 1
            end
            
            current = tonumber(current)
            
            if current < limit then
                redis.call('INCR', key)
                return 1
            end
            
            return 0
            """;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = new DefaultRedisScript<>();
        this.rateLimitScript.setScriptText(RATE_LIMIT_LUA);
        this.rateLimitScript.setResultType(Long.class);
    }

    @Override
    public boolean tryConsume(String key, int limit, int periodSeconds) {
        try {
            String redisKey = "ratelimit:" + key;
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    List.of(redisKey),
                    String.valueOf(limit),
                    String.valueOf(periodSeconds)
            );
            return result == 1;
        } catch (Exception e) {
            logger.error("Redis rate limit error for key: {}", key, e);
            return true;
        }
    }

    @Override
    public long getRemainingTokens(String key, int limit, int periodSeconds) {
        try {
            String redisKey = "ratelimit:" + key;
            String value = redisTemplate.opsForValue().get(redisKey);
            if (value == null) {
                return limit;
            }
            long current = Long.parseLong(value);
            return Math.max(0, limit - current);
        } catch (Exception e) {
            logger.warn("Failed to get remaining tokens for key: {}", key, e);
            return limit;
        }
    }

    @Override
    public void reset(String key) {
        try {
            String redisKey = "ratelimit:" + key;
            redisTemplate.delete(redisKey);
        } catch (Exception e) {
            logger.error("Failed to reset rate limit for key: {}", key, e);
        }
    }
}

