package me.internalizable.knowisearch.controller;

import me.internalizable.knowisearch.cache.response.CachedChatResponse;
import me.internalizable.knowisearch.cache.QueryNormalizer;
import me.internalizable.knowisearch.cache.types.TieredCacheService;
import me.internalizable.knowisearch.dto.ChatRequest;
import me.internalizable.knowisearch.dto.ChatResponse;
import me.internalizable.knowisearch.service.ChatService;
import me.internalizable.knowisearch.service.KnowiCrawlerService;
import me.internalizable.knowisearch.service.KnowiCrawlerService.CrawledPage;
import me.internalizable.knowisearch.service.KnowiCrawlerService.DocChunk;
import me.internalizable.knowisearch.service.LuceneIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final KnowiCrawlerService crawlerService;
    private final LuceneIndexService indexService;
    private final TieredCacheService<CachedChatResponse> cacheService;
    private final QueryNormalizer queryNormalizer;
    private final double similarityThreshold;

    public ChatController(ChatService chatService,
                          KnowiCrawlerService crawlerService,
                          LuceneIndexService indexService,
                          TieredCacheService<CachedChatResponse> chatCache,
                          QueryNormalizer queryNormalizer,
                          @Value("${cache.similarity-threshold:0.70}") double similarityThreshold) {
        this.chatService = chatService;
        this.crawlerService = crawlerService;
        this.indexService = indexService;
        this.cacheService = chatCache;
        this.queryNormalizer = queryNormalizer;
        this.similarityThreshold = similarityThreshold;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    /**
     * Crawl Knowi docs and rebuild the search index
     */
    @PostMapping("/ingest")
    public Map<String, Object> ingest() {
        try {
            logger.info("Starting documentation ingestion...");

            List<CrawledPage> pages = crawlerService.crawl();
            List<DocChunk> chunks = crawlerService.chunkPages(pages);
            indexService.rebuildIndex(chunks);
            cacheService.clear();

            logger.info("Ingestion complete. Pages: {}, Chunks: {}", pages.size(), chunks.size());

            return Map.of(
                    "success", true,
                    "pagesIndexed", pages.size(),
                    "chunksCreated", chunks.size(),
                    "message", "Documentation indexed successfully"
            );
        } catch (Exception e) {
            logger.error("Ingestion failed", e);
            return Map.of(
                    "success", false,
                    "error", e.getMessage()
            );
        }
    }

    /**
     * Get index status
     */
    @GetMapping("/index/status")
    public Map<String, Object> indexStatus() {
        boolean exists = indexService.indexExists();
        int size = indexService.getIndexSize();

        return Map.of(
                "indexed", exists,
                "documentCount", size,
                "message", exists ? "Index is ready with " + size + " documents" : "Index not built. Click 'Rebuild Index' to start."
        );
    }

    /**
     * Get cache statistics
     */
    @GetMapping("/cache/stats")
    public Map<String, Object> cacheStats() {
        return cacheService.getStats();
    }

    /**
     * Clear all caches
     */
    @PostMapping("/cache/clear")
    public Map<String, Object> clearCache() {
        cacheService.clear();
        return Map.of(
                "success", true,
                "message", "All caches cleared"
        );
    }

    /**
     * Debug endpoint: Analyze how two queries are normalized and their similarity
     */
    @GetMapping("/cache/debug/similarity")
    public Map<String, Object> debugSimilarity(
            @RequestParam String query1,
            @RequestParam String query2) {

        Map<String, Object> result = new LinkedHashMap<>();

        // Query 1 analysis
        String normalized1 = queryNormalizer.normalize(query1);
        Set<String> tokens1 = queryNormalizer.tokenize(query1);
        String cacheKey1 = queryNormalizer.generateCacheKey(query1);

        // Query 2 analysis
        String normalized2 = queryNormalizer.normalize(query2);
        Set<String> tokens2 = queryNormalizer.tokenize(query2);
        String cacheKey2 = queryNormalizer.generateCacheKey(query2);

        // Similarity calculation
        double similarity = queryNormalizer.calculateSimilarity(query1, query2);
        boolean wouldMatch = similarity >= similarityThreshold;

        result.put("query1", Map.of(
                "original", query1,
                "normalized", normalized1,
                "tokens", tokens1,
                "cacheKey", cacheKey1
        ));

        result.put("query2", Map.of(
                "original", query2,
                "normalized", normalized2,
                "tokens", tokens2,
                "cacheKey", cacheKey2
        ));

        result.put("similarity", String.format("%.2f%%", similarity * 100));
        result.put("threshold", String.format("%.2f%%", similarityThreshold * 100));
        result.put("wouldMatchCache", wouldMatch);
        result.put("sameExactKey", cacheKey1.equals(cacheKey2));

        return result;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}

