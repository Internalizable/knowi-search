package me.internalizable.knowisearch.service;

import lombok.Getter;
import lombok.Setter;
import me.internalizable.knowisearch.cache.types.LocalCacheService;
import me.internalizable.knowisearch.service.KnowiCrawlerService.DocChunk;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
@Getter
public class LuceneIndexService {

    private static final Logger logger = LoggerFactory.getLogger(LuceneIndexService.class);

    private final Path indexDir;
    private final Analyzer analyzer;
    private final LocalCacheService<String, Object> searchCache;

    @Setter
    private volatile DirectoryReader cachedReader;
    @Setter
    private volatile IndexSearcher cachedSearcher;
    private final Object readerLock = new Object();

    public record SearchResult(String url, String title, String chunk, float score) {}

    public LuceneIndexService(
            @Value("${knowi.docs.index-dir}") String indexDirPath,
            LocalCacheService<String, Object> searchL1Cache) {
        this.indexDir = Path.of(indexDirPath);
        this.analyzer = new StandardAnalyzer();
        this.searchCache = searchL1Cache;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(indexDir);
            logger.info("Lucene index directory: {}", indexDir.toAbsolutePath());
            refreshReader();
        } catch (IOException e) {
            logger.error("Failed to create index directory", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        closeReader();
    }

    private void closeReader() {
        synchronized (readerLock) {
            if (cachedReader != null) {
                try {
                    cachedReader.close();
                } catch (IOException e) {
                    logger.warn("Error closing reader", e);
                }
                cachedReader = null;
                cachedSearcher = null;
            }
        }
    }

    private void refreshReader() {
        synchronized (readerLock) {
            try {
                if (!indexExists()) {
                    closeReader();
                    return;
                }

                FSDirectory dir = FSDirectory.open(indexDir);
                if (cachedReader == null) {
                    cachedReader = DirectoryReader.open(dir);
                    cachedSearcher = new IndexSearcher(cachedReader);
                } else {
                    DirectoryReader newReader = DirectoryReader.openIfChanged(cachedReader);
                    if (newReader != null) {
                        cachedReader.close();
                        cachedReader = newReader;
                        cachedSearcher = new IndexSearcher(cachedReader);
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not refresh reader: {}", e.getMessage());
            }
        }
    }

    private IndexSearcher getSearcher() {
        synchronized (readerLock) {
            if (cachedSearcher == null) {
                refreshReader();
            }
            return cachedSearcher;
        }
    }

    public boolean indexExists() {
        try {
            if (!Files.exists(indexDir)) {
                return false;
            }
            try (FSDirectory dir = FSDirectory.open(indexDir)) {
                return DirectoryReader.indexExists(dir);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public int getIndexSize() {
        IndexSearcher searcher = getSearcher();
        if (searcher != null) {
            return searcher.getIndexReader().numDocs();
        }
        return 0;
    }

    public void rebuildIndex(List<DocChunk> chunks) throws IOException {
        closeReader();
        Files.createDirectories(indexDir);

        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        config.setRAMBufferSizeMB(64);

        try (FSDirectory dir = FSDirectory.open(indexDir);
             IndexWriter writer = new IndexWriter(dir, config)) {

            for (DocChunk chunk : chunks) {
                Document doc = new Document();
                doc.add(new StoredField("url", chunk.url()));
                doc.add(new StoredField("title", chunk.title() != null ? chunk.title() : ""));
                doc.add(new StoredField("chunk", chunk.chunk()));
                doc.add(new StoredField("chunkIndex", chunk.chunkIndex()));
                doc.add(new TextField("content", chunk.chunk(), Field.Store.NO));

                if (chunk.title() != null && !chunk.title().isBlank()) {
                    doc.add(new TextField("titleText", chunk.title(), Field.Store.NO));
                }

                String urlKeywords = extractUrlKeywords(chunk.url());
                if (!urlKeywords.isBlank()) {
                    doc.add(new TextField("urlKeywords", urlKeywords, Field.Store.NO));
                }

                writer.addDocument(doc);
            }

            writer.commit();
            writer.forceMerge(1);
            logger.info("Index rebuilt with {} documents", chunks.size());
        }

        searchCache.clear();
        refreshReader();
    }

    private String extractUrlKeywords(String url) {
        if (url == null) return "";
        return url.replaceAll("https?://[^/]+/", "")
                  .replaceAll("\\.html?", "")
                  .replaceAll("[/-]", " ")
                  .toLowerCase();
    }

    @SuppressWarnings("unchecked")
    public List<SearchResult> searchWithTitleBoost(String queryText, int topK) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        String cacheKey = "search:" + queryText.toLowerCase().trim() + ":" + topK;
        var cachedOpt = searchCache.get(cacheKey);
        if (cachedOpt.isPresent()) {
            logger.debug("Cache hit for query: {}", queryText);
            return (List<SearchResult>) cachedOpt.get();
        }

        List<SearchResult> results = new ArrayList<>();
        IndexSearcher searcher = getSearcher();

        if (searcher == null) {
            logger.warn("Index not available. Please run ingest first.");
            return results;
        }

        try {
            String escapedQuery = QueryParser.escape(queryText);

            BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();

            QueryParser contentParser = new QueryParser("content", analyzer);
            Query contentQuery = contentParser.parse(escapedQuery);
            booleanQuery.add(contentQuery, BooleanClause.Occur.SHOULD);

            QueryParser titleParser = new QueryParser("titleText", analyzer);
            Query titleQuery = new BoostQuery(titleParser.parse(escapedQuery), 3.0f);
            booleanQuery.add(titleQuery, BooleanClause.Occur.SHOULD);

            QueryParser urlParser = new QueryParser("urlKeywords", analyzer);
            Query urlQuery = new BoostQuery(urlParser.parse(escapedQuery), 2.0f);
            booleanQuery.add(urlQuery, BooleanClause.Occur.SHOULD);

            TopDocs topDocs = searcher.search(booleanQuery.build(), topK);

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                results.add(new SearchResult(
                        doc.get("url"),
                        doc.get("title"),
                        doc.get("chunk"),
                        scoreDoc.score
                ));
            }

            searchCache.put(cacheKey, results);
            logger.debug("Search for '{}' returned {} results", queryText, results.size());

        } catch (Exception e) {
            logger.error("Search failed for query: {}", queryText, e);
        }

        return results;
    }

    public List<SearchResult> search(String queryText, int topK) {
        return searchWithTitleBoost(queryText, topK);
    }
}

