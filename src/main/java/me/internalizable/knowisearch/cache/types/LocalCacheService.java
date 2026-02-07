package me.internalizable.knowisearch.cache.types;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Builder;
import lombok.Getter;
import me.internalizable.knowisearch.cache.StatisticalCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Getter
public class LocalCacheService<K, V> implements StatisticalCacheService<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(LocalCacheService.class);

    private final Cache<K, V> cache;
    private final String name;

    @Builder
    public LocalCacheService(String name, int maxSize, Duration ttl) {
        this.name = name != null ? name : "default";
        int size = maxSize > 0 ? maxSize : 500;
        Duration duration = ttl != null ? ttl : Duration.ofMinutes(30);
        this.cache = Caffeine.newBuilder()
                .maximumSize(size)
                .expireAfterWrite(duration)
                .recordStats()
                .build();
    }

    @Override
    public Optional<V> get(K key) {
        V value = cache.getIfPresent(key);
        if (value != null) {
            logger.trace("[{}] Cache hit for key: {}", name, key);
        }
        return Optional.ofNullable(value);
    }

    @Override
    public void put(K key, V value) {
        cache.put(key, value);
        logger.trace("[{}] Cached value for key: {}", name, key);
    }

    @Override
    public void evict(K key) {
        cache.invalidate(key);
        logger.trace("[{}] Evicted key: {}", name, key);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
        logger.info("[{}] Cache cleared", name);
    }

    @Override
    public boolean containsKey(K key) {
        return cache.getIfPresent(key) != null;
    }

    @Override
    public long size() {
        return cache.estimatedSize();
    }

    @Override
    public Map<String, Object> getStats() {
        var stats = cache.stats();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("type", "local");
        result.put("size", cache.estimatedSize());
        result.put("hits", stats.hitCount());
        result.put("misses", stats.missCount());
        result.put("hit_rate", String.format("%.2f%%", stats.hitRate() * 100));
        result.put("evictions", stats.evictionCount());
        result.put("load_success", stats.loadSuccessCount());
        result.put("load_failure", stats.loadFailureCount());
        return result;
    }

    @Override
    public double getHitRate() {
        return cache.stats().hitRate() * 100;
    }

    @Override
    public long getHitCount() {
        return cache.stats().hitCount();
    }

    @Override
    public long getMissCount() {
        return cache.stats().missCount();
    }
}

