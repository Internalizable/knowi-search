package me.internalizable.knowisearch.config;

import me.internalizable.knowisearch.ratelimit.LocalRateLimiter;
import me.internalizable.knowisearch.ratelimit.RateLimiter;
import me.internalizable.knowisearch.ratelimit.RedisRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RateLimitConfig {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitConfig.class);

    @Bean
    @ConditionalOnProperty(
            name = "rate-limit.use-redis",
            havingValue = "true",
            matchIfMissing = false
    )
    public RateLimiter redisRateLimiter(StringRedisTemplate redisTemplate) {
        return new RedisRateLimiter(redisTemplate);
    }

    @Bean
    @ConditionalOnProperty(
            name = "rate-limit.use-redis",
            havingValue = "false",
            matchIfMissing = true
    )
    public RateLimiter localRateLimiter() {
        return new LocalRateLimiter();
    }
}

