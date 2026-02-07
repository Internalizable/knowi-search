package me.internalizable.knowisearch.cache;

import java.util.Optional;


public interface CacheService<K, V> {
    Optional<V> get(K key);

    void put(K key, V value);

    void evict(K key);

    void clear();

    boolean containsKey(K key);

    long size();
}

