package me.internalizable.knowisearch.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PerplexityResponse(
        String id,
        String model,
        String object,
        Long created,
        List<Choice> choices,
        Usage usage,
        List<String> citations
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            Integer index,
            @JsonProperty("finish_reason") String finishReason,
            Message message,
            Delta delta
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens
    ) {}

    public String getContent() {
        if (choices != null && !choices.isEmpty()) {
            var choice = choices.getFirst();
            if (choice.message() != null) {
                return choice.message().content();
            }
        }
        return null;
    }
}

