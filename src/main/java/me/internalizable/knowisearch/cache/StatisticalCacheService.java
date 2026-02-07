package me.internalizable.knowisearch.cache;

import java.util.Map;


public interface StatisticalCacheService<K, V> extends CacheService<K, V> {

    /**
     * Get cache statistics
     * @return Map of statistic name to value
     */
    Map<String, Object> getStats();

    /**
     * Get the cache hit rate
     * @return Hit rate as percentage (0-100)
     */
    double getHitRate();

    /**
     * Get total number of hits
     * @return Hit count
     */
    long getHitCount();

    /**
     * Get total number of misses
     * @return Miss count
     */
    long getMissCount();
}

