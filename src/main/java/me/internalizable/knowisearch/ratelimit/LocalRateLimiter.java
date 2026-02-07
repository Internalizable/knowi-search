package me.internalizable.knowisearch.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class LocalRateLimiter implements RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(LocalRateLimiter.class);

    private final Cache<String, Bucket> bucketCache;

    public LocalRateLimiter() {
        this.bucketCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build();
    }

    @Override
    public boolean tryConsume(String key, int limit, int periodSeconds) {
        Bucket bucket = bucketCache.get(key, k -> createBucket(limit, periodSeconds));
        return bucket.tryConsume(1);
    }

    @Override
    public long getRemainingTokens(String key, int limit, int periodSeconds) {
        Bucket bucket = bucketCache.getIfPresent(key);
        return bucket != null ? bucket.getAvailableTokens() : limit;
    }

    @Override
    public void reset(String key) {
        bucketCache.invalidate(key);
    }

    private Bucket createBucket(int limit, int periodSeconds) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit)
                        .refillGreedy(limit, Duration.ofSeconds(periodSeconds))
                        .build())
                .build();
    }
}

