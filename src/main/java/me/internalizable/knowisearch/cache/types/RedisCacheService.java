package me.internalizable.knowisearch.cache.types;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import me.internalizable.knowisearch.cache.StatisticalCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Getter
public class RedisCacheService<V> implements StatisticalCacheService<String, V> {

    private static final Logger logger = LoggerFactory.getLogger(RedisCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration ttl;
    private final TypeReference<V> typeReference;
    private final Class<V> valueClass;

    private long hitCount = 0;
    private long missCount = 0;

    @Builder
    public RedisCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            String keyPrefix,
            Duration ttl,
            Class<V> valueClass,
            TypeReference<V> typeReference) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.keyPrefix = keyPrefix != null ? (keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":") : "cache:";
        this.ttl = ttl != null ? ttl : Duration.ofMinutes(60);
        this.valueClass = valueClass;
        this.typeReference = typeReference;

        if (redisTemplate == null) {
            throw new IllegalStateException("RedisTemplate is required");
        }
        if (valueClass == null && typeReference == null) {
            throw new IllegalStateException("Either valueClass or typeReference must be specified");
        }

        logger.info("RedisCacheService initialized - prefix: {}, ttl: {}", this.keyPrefix, this.ttl);
    }

    @Override
    public Optional<V> get(String key) {
        try {
            String json = redisTemplate.opsForValue().get(keyPrefix + key);
            if (json != null) {
                V value = deserialize(json);
                hitCount++;
                logger.trace("[Redis] Cache hit for key: {}", key);
                return Optional.ofNullable(value);
            }
        } catch (Exception e) {
            logger.warn("[Redis] Error reading key {}: {}", key, e.getMessage());
        }
        missCount++;
        return Optional.empty();
    }

    @Override
    public void put(String key, V value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(keyPrefix + key, json, ttl);
            logger.trace("[Redis] Cached value for key: {}", key);
        } catch (JsonProcessingException e) {
            logger.warn("[Redis] Error serializing value for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public void evict(String key) {
        redisTemplate.delete(keyPrefix + key);
        logger.trace("[Redis] Evicted key: {}", key);
    }

    @Override
    public void clear() {
        try {
            Set<String> keys = redisTemplate.keys(keyPrefix + "*");
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
                logger.info("[Redis] Cleared {} keys with prefix: {}", keys.size(), keyPrefix);
            }
        } catch (Exception e) {
            logger.warn("[Redis] Error clearing cache: {}", e.getMessage());
        }
        hitCount = 0;
        missCount = 0;
    }

    @Override
    public boolean containsKey(String key) {
        return redisTemplate.hasKey(keyPrefix + key);
    }

    @Override
    public long size() {
        try {
            Set<String> keys = redisTemplate.keys(keyPrefix + "*");
            return keys.size();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("type", "redis");
        stats.put("prefix", keyPrefix);
        stats.put("ttl_seconds", ttl.getSeconds());
        stats.put("size", size());
        stats.put("hits", hitCount);
        stats.put("misses", missCount);
        stats.put("hit_rate", String.format("%.2f%%", getHitRate()));
        return stats;
    }

    @Override
    public double getHitRate() {
        long total = hitCount + missCount;
        return total > 0 ? (double) hitCount / total * 100 : 0.0;
    }

    @Override
    public long getHitCount() {
        return hitCount;
    }

    @Override
    public long getMissCount() {
        return missCount;
    }

    /**
     * Check if Redis connection is available
     */
    public boolean isAvailable() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private V deserialize(String json) throws JsonProcessingException {
        if (typeReference != null) {
            return objectMapper.readValue(json, typeReference);
        } else {
            return objectMapper.readValue(json, valueClass);
        }
    }
}

