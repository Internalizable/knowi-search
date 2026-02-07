package me.internalizable.knowisearch.dto;

import java.util.List;

public record ChatRequest(String message, List<MessageHistory> history) {

    public record MessageHistory(String role, String content) {}
}

