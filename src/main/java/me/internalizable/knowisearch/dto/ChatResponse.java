package me.internalizable.knowisearch.dto;

import java.util.List;

public record ChatResponse(
        String message,
        boolean success,
        String error,
        List<String> sources,
        boolean fromCache
) {

    public static ChatResponse success(String message) {
        return new ChatResponse(message, true, null, List.of(), false);
    }

    public static ChatResponse success(String message, List<String> sources) {
        return new ChatResponse(message, true, null, sources != null ? sources : List.of(), false);
    }

    public static ChatResponse successFromCache(String message, List<String> sources) {
        return new ChatResponse(message, true, null, sources != null ? sources : List.of(), true);
    }

    public static ChatResponse error(String error) {
        return new ChatResponse(null, false, error, List.of(), false);
    }
}

