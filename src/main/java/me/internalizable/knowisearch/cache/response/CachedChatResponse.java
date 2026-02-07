package me.internalizable.knowisearch.cache.response;

import java.util.List;

public record CachedChatResponse(
        String response,
        List<String> sources,
        long timestamp
) {
    public static CachedChatResponse of(String response, List<String> sources) {
        return new CachedChatResponse(response, sources, System.currentTimeMillis());
    }

    public boolean isExpired(long ttlMillis) {
        return System.currentTimeMillis() - timestamp > ttlMillis;
    }

    public long getAgeMillis() {
        return System.currentTimeMillis() - timestamp;
    }
}

