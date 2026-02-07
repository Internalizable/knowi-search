package me.internalizable.knowisearch.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PerplexityRequest(
        String model,
        List<Message> messages,
        @JsonProperty("max_tokens") Integer maxTokens,
        Double temperature,
        @JsonProperty("search_domain_filter") List<String> searchDomainFilter,
        @JsonProperty("return_related_questions") Boolean returnRelatedQuestions,
        @JsonProperty("search_recency_filter") String searchRecencyFilter
) {

    public record Message(String role, String content) {}

    public static PerplexityRequest forKnowiDocs(String model, List<Message> messages) {
        return new PerplexityRequest(
                model,
                messages,
                2048,
                0.2,
                List.of("knowi.com"),
                false,
                null
        );
    }
}

