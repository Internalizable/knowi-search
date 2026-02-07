package me.internalizable.knowisearch.cache.types;

import lombok.Builder;
import lombok.Getter;
import me.internalizable.knowisearch.cache.QueryNormalizer;
import me.internalizable.knowisearch.cache.StatisticalCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Builder
public class TieredCacheService<V> implements StatisticalCacheService<String, V> {

    private static final Logger logger = LoggerFactory.getLogger(TieredCacheService.class);

    private final LocalCacheService<String, V> l1Cache;
    private final RedisCacheService<V> l2Cache;
    private final QueryNormalizer queryNormalizer;
    @Builder.Default
    private final double similarityThreshold = 0.85;

    @Builder.Default
    private final Map<String, String> queryFingerprints = new ConcurrentHashMap<>();

    @Override
    public Optional<V> get(String query) {
        String normalizedQuery = queryNormalizer.normalize(query);
        String cacheKey = queryNormalizer.generateCacheKey(normalizedQuery);

        Optional<V> l1Result = l1Cache.get(cacheKey);
        if (l1Result.isPresent()) {
            return l1Result;
        }

        Optional<V> fuzzyResult = findFuzzyMatch(normalizedQuery);
        if (fuzzyResult.isPresent()) {
            return fuzzyResult;
        }

        if (l2Cache != null) {
            Optional<V> l2Result = l2Cache.get(cacheKey);
            if (l2Result.isPresent()) {
                l1Cache.put(cacheKey, l2Result.get());
                queryFingerprints.put(normalizedQuery, cacheKey);
                return l2Result;
            }
        }

        return Optional.empty();
    }

    @Override
    public void put(String query, V value) {
        String normalizedQuery = queryNormalizer.normalize(query);
        String cacheKey = queryNormalizer.generateCacheKey(normalizedQuery);

        l1Cache.put(cacheKey, value);
        queryFingerprints.put(normalizedQuery, cacheKey);

        if (l2Cache != null) {
            l2Cache.put(cacheKey, value);
            logger.info("Also stored in L2 (Redis)");
        }
    }

    @Override
    public void evict(String query) {
        String normalizedQuery = queryNormalizer.normalize(query);
        String cacheKey = queryNormalizer.generateCacheKey(normalizedQuery);

        l1Cache.evict(cacheKey);
        queryFingerprints.remove(normalizedQuery);

        if (l2Cache != null) {
            l2Cache.evict(cacheKey);
        }
    }

    @Override
    public void clear() {
        l1Cache.clear();
        queryFingerprints.clear();

        if (l2Cache != null) {
            l2Cache.clear();
        }
    }

    @Override
    public boolean containsKey(String query) {
        String normalizedQuery = queryNormalizer.normalize(query);
        String cacheKey = queryNormalizer.generateCacheKey(normalizedQuery);
        return l1Cache.containsKey(cacheKey) || (l2Cache != null && l2Cache.containsKey(cacheKey));
    }

    @Override
    public long size() {
        return l1Cache.size();
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        stats.put("l1", l1Cache.getStats());
        stats.put("fingerprints", queryFingerprints.size());
        stats.put("similarity_threshold", similarityThreshold);

        if (l2Cache != null) {
            stats.put("l2", l2Cache.getStats());
        } else {
            stats.put("l2", "disabled");
        }

        long totalHits = l1Cache.getHitCount() + (l2Cache != null ? l2Cache.getHitCount() : 0);
        long totalMisses = l1Cache.getMissCount() + (l2Cache != null ? l2Cache.getMissCount() : 0);
        long total = totalHits + totalMisses;

        stats.put("total_hits", totalHits);
        stats.put("total_misses", totalMisses);
        stats.put("combined_hit_rate", total > 0 ? String.format("%.2f%%", (double) totalHits / total * 100) : "N/A");

        return stats;
    }

    @Override
    public double getHitRate() {
        return l1Cache.getHitRate(); // L1 is primary
    }

    @Override
    public long getHitCount() {
        return l1Cache.getHitCount() + (l2Cache != null ? l2Cache.getHitCount() : 0);
    }

    @Override
    public long getMissCount() {
        return l1Cache.getMissCount() + (l2Cache != null ? l2Cache.getMissCount() : 0);
    }

    public boolean isL2Available() {
        return l2Cache != null && l2Cache.isAvailable();
    }

    private Optional<V> findFuzzyMatch(String normalizedQuery) {
        Set<String> queryTokens = queryNormalizer.tokenize(normalizedQuery);

        if (queryTokens.isEmpty()) {
            return Optional.empty();
        }

        String bestMatchKey = null;
        double bestScore = 0;

        for (Map.Entry<String, String> entry : queryFingerprints.entrySet()) {
            Set<String> cachedTokens = queryNormalizer.tokenize(entry.getKey());
            double similarity = queryNormalizer.calculateJaccardSimilarity(queryTokens, cachedTokens);

            if (similarity > bestScore && similarity >= similarityThreshold) {
                bestScore = similarity;
                bestMatchKey = entry.getValue();
            }
        }

        if (bestMatchKey != null) {
            Optional<V> result = l1Cache.get(bestMatchKey);
            if (result.isPresent()) {
                logger.debug("Fuzzy match found with similarity: {:.2f}", bestScore);
                return result;
            }
        }

        return Optional.empty();
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }
}

