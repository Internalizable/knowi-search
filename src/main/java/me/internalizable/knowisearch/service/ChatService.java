package me.internalizable.knowisearch.service;

import me.internalizable.knowisearch.cache.response.CachedChatResponse;
import me.internalizable.knowisearch.cache.types.TieredCacheService;
import me.internalizable.knowisearch.dto.ChatRequest;
import me.internalizable.knowisearch.dto.ChatResponse;
import me.internalizable.knowisearch.dto.PerplexityRequest;
import me.internalizable.knowisearch.dto.PerplexityResponse;
import me.internalizable.knowisearch.service.LuceneIndexService.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Chat service that handles user queries about Knowi documentation.
 * Uses RAG (Retrieval Augmented Generation) with Lucene search and Perplexity AI.
 *
 * Responsibilities:
 * - Orchestrate the chat flow (search -> build context -> call AI)
 * - Build prompts with retrieved documentation context
 * - Handle errors gracefully
 *
 * Caching is delegated to TieredCacheService (SRP).
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant specialized in answering questions about Knowi, 
            a Business Intelligence and Analytics platform.
            
            IMPORTANT INSTRUCTIONS:
            1. Use ONLY the provided documentation sources to answer questions
            2. If the sources don't contain enough information, say so clearly
            3. Be concise and accurate - avoid unnecessary repetition
            4. Format your response with clear structure:
               - Use **bold** for important terms
               - Use numbered lists for step-by-step instructions
               - Use bullet points for feature lists
            5. Reference sources using [1], [2], etc. notation matching the SOURCE numbers provided
            6. Do NOT include raw URLs in your response - the citation numbers will be linked automatically
            7. If unsure about something, say so rather than guessing
            
            The user's question will be accompanied by relevant documentation excerpts.
            """;

    private final RestClient perplexityRestClient;
    private final LuceneIndexService luceneIndexService;
    private final TieredCacheService<CachedChatResponse> cacheService;
    private final String model;

    public ChatService(
            RestClient perplexityRestClient,
            LuceneIndexService luceneIndexService,
            TieredCacheService<CachedChatResponse> chatCache,
            @Value("${perplexity.model}") String model) {
        this.perplexityRestClient = perplexityRestClient;
        this.luceneIndexService = luceneIndexService;
        this.cacheService = chatCache;
        this.model = model;
    }

    public ChatResponse chat(ChatRequest request) {
        try {
            String userMessage = request.message();
            if (userMessage == null || userMessage.isBlank()) {
                return ChatResponse.success("Please ask a question about Knowi documentation.", List.of());
            }

            List<SearchResult> searchResults = luceneIndexService.searchWithTitleBoost(userMessage, 8);
            Set<String> sources = buildSourceSet(searchResults);

            Optional<CachedChatResponse> cached = cacheService.get(userMessage);

            if (cached.isPresent()) {
                return ChatResponse.successFromCache(cached.get().response(), cached.get().sources());
            }

            String documentContext = buildDocumentContext(searchResults);

            List<PerplexityRequest.Message> messages = buildMessages(request, documentContext);

            logger.info("Sending request to Perplexity API for question: {}", truncate(userMessage, 50));
            PerplexityResponse response = callPerplexityApi(messages);

            if (response != null && response.getContent() != null) {
                logger.info("Received successful response from Perplexity API");

                if (response.citations() != null) {
                    sources.addAll(response.citations());
                }

                String content = response.getContent();
                List<String> sourceList = new ArrayList<>(sources);

                CachedChatResponse cachedResponse = CachedChatResponse.of(content, sourceList);
                cacheService.put(userMessage, cachedResponse);

                return ChatResponse.success(content, sourceList);
            } else {
                logger.warn("Empty response from Perplexity API");
                return ChatResponse.error("No response received from AI");
            }

        } catch (Exception e) {
            return handleError(e);
        }
    }

    private Set<String> buildSourceSet(List<SearchResult> searchResults) {
        Set<String> sources = new LinkedHashSet<>();
        for (SearchResult result : searchResults) {
            sources.add(result.url());
        }
        return sources;
    }

    private String buildDocumentContext(List<SearchResult> searchResults) {
        if (searchResults.isEmpty()) {
            return "";
        }

        StringBuilder contextBuilder = new StringBuilder();
        int sourceNum = 1;

        for (SearchResult result : searchResults) {
            contextBuilder.append("SOURCE [").append(sourceNum++).append("]:\n");
            contextBuilder.append("URL: ").append(result.url()).append("\n");
            if (result.title() != null && !result.title().isBlank()) {
                contextBuilder.append("TITLE: ").append(result.title()).append("\n");
            }
            contextBuilder.append("CONTENT:\n").append(result.chunk()).append("\n\n");
        }

        return contextBuilder.toString();
    }

    private List<PerplexityRequest.Message> buildMessages(ChatRequest request, String documentContext) {
        List<PerplexityRequest.Message> messages = new ArrayList<>();

        messages.add(new PerplexityRequest.Message("system", SYSTEM_PROMPT));

        if (request.history() != null && !request.history().isEmpty()) {
            List<ChatRequest.MessageHistory> recentHistory = request.history();
            if (recentHistory.size() > 10) {
                recentHistory = recentHistory.subList(recentHistory.size() - 10, recentHistory.size());
            }
            for (ChatRequest.MessageHistory historyItem : recentHistory) {
                messages.add(new PerplexityRequest.Message(historyItem.role(), historyItem.content()));
            }
        }

        String userMessageWithContext = buildUserMessageWithContext(request.message(), documentContext);
        messages.add(new PerplexityRequest.Message("user", userMessageWithContext));

        return messages;
    }

    private String buildUserMessageWithContext(String userMessage, String documentContext) {
        if (!documentContext.isBlank()) {
            return "Question: " + userMessage +
                    "\n\nHere are relevant Knowi documentation sources to help answer this question:\n\n" +
                    documentContext;
        } else {
            return "Question: " + userMessage +
                    "\n\nNote: No pre-indexed documentation was found for this query. " +
                    "Please answer based on your knowledge of Knowi from knowi.com/docs if possible.";
        }
    }

    private PerplexityResponse callPerplexityApi(List<PerplexityRequest.Message> messages) {
        PerplexityRequest perplexityRequest = PerplexityRequest.forKnowiDocs(model, messages);

        return perplexityRestClient.post()
                .body(perplexityRequest)
                .retrieve()
                .body(PerplexityResponse.class);
    }

    private ChatResponse handleError(Exception e) {
        logger.error("Error calling Perplexity API", e);

        String errorMessage = e.getMessage();
        if (errorMessage != null) {
            if (errorMessage.contains("401")) {
                return ChatResponse.error("API authentication failed. Please check your Perplexity API key is valid. Get a new key from: https://www.perplexity.ai/settings/api");
            } else if (errorMessage.contains("429")) {
                return ChatResponse.error("Rate limit exceeded. Please wait a moment and try again.");
            } else if (errorMessage.contains("403")) {
                return ChatResponse.error("API access forbidden. Your API key may not have the required permissions.");
            }
        }

        return ChatResponse.error("Failed to get response: " + (errorMessage != null ? errorMessage : "Unknown error"));
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}

