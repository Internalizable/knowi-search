package me.internalizable.knowisearch.cache;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.en.PorterStemFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.CharsRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Query normalizer using Apache Lucene's text analysis pipeline.
 *
 * Features:
 * - Proper tokenization (handles punctuation, special chars)
 * - Porter stemming (dashboards → dashboard, running → run)
 * - Stop word removal (English stop words)
 * - ASCII folding (café → cafe)
 * - Domain-specific synonyms (visualization = chart = graph)
 *
 * This is much more robust than hand-coded regex patterns.
 */
@Component
public class QueryNormalizer {

    private static final Logger logger = LoggerFactory.getLogger(QueryNormalizer.class);

    private Analyzer queryAnalyzer;
    private SynonymMap synonymMap;

    @PostConstruct
    public void init() {
        try {
            this.synonymMap = buildSynonymMap();
            this.queryAnalyzer = createQueryAnalyzer();
            logger.info("QueryNormalizer initialized with Lucene analysis pipeline");
        } catch (IOException e) {
            logger.error("Failed to initialize QueryNormalizer", e);
            // Fallback to simple analyzer
            this.queryAnalyzer = new EnglishAnalyzer();
        }
    }

    /**
     * Build domain-specific synonym mappings for Knowi terminology
     */
    private SynonymMap buildSynonymMap() throws IOException {
        SynonymMap.Builder builder = new SynonymMap.Builder(true);

        // === Question/Request synonyms (IMPORTANT for cache matching) ===
        // All question words map to a single canonical form
        addSynonymGroup(builder, "explain", "overview", "description", "summary", "introduction", "intro", "describe", "about");
        addSynonymGroup(builder, "show", "display", "give", "provide", "tell", "list", "get");
        addSynonymGroup(builder, "how", "way", "steps", "process", "method", "guide", "tutorial");
        addSynonymGroup(builder, "what", "which", "define", "meaning", "whats");

        // Combine action verbs that mean "perform/execute"
        addSynonymGroup(builder, "do", "perform", "execute", "run", "use", "using");

        // === Knowi-specific synonyms ===

        // Visualization synonyms
        addSynonymGroup(builder, "chart", "graph", "visualization", "viz", "visual", "plot");

        // Dashboard synonyms
        addSynonymGroup(builder, "dashboard", "dash", "board", "panel");

        // Query synonyms
        addSynonymGroup(builder, "query", "queries", "sql", "search");

        // Data source synonyms
        addSynonymGroup(builder, "datasource", "datasources", "connection", "database", "db", "source");

        // Widget synonyms
        addSynonymGroup(builder, "widget", "widgets", "component", "tile", "element");

        // Report synonyms
        addSynonymGroup(builder, "report", "reports", "reporting");

        // Alert synonyms
        addSynonymGroup(builder, "alert", "alerts", "notification", "notifications");

        // Filter synonyms
        addSynonymGroup(builder, "filter", "filters", "filtering", "criteria");

        // User synonyms
        addSynonymGroup(builder, "user", "users", "account", "accounts", "member");

        // Permission synonyms
        addSynonymGroup(builder, "permission", "permissions", "access", "role", "roles", "privilege");

        // Analytics synonyms
        addSynonymGroup(builder, "analytics", "analysis", "insights", "bi", "intelligence");

        // Self-service / Adhoc synonyms - IMPORTANT: keep these together
        addSynonymGroup(builder, "selfservice", "adhoc", "explore", "exploration", "selfserve");

        // Action synonyms
        addSynonymGroup(builder, "create", "add", "new", "make", "build");
        addSynonymGroup(builder, "delete", "remove", "drop", "clear");
        addSynonymGroup(builder, "edit", "update", "modify", "change", "alter");
        addSynonymGroup(builder, "configure", "setup", "config", "settings", "set");
        addSynonymGroup(builder, "connect", "link", "integrate", "join");

        return builder.build();
    }

    /**
     * Custom stop words - common words that add noise to query matching
     */
    private static final Set<String> CUSTOM_STOP_WORDS = Set.of(
            // Pronouns
            "i", "me", "my", "you", "your", "we", "our", "they", "them",
            // Common verbs that don't add meaning
            "can", "could", "would", "should", "will", "shall", "may", "might",
            "want", "need", "like", "please", "help", "let", "lets",
            // Articles and prepositions (supplement English stop words)
            "the", "a", "an", "in", "on", "at", "to", "for", "of", "with", "by",
            // Filler words
            "just", "really", "very", "also", "some", "any", "thing", "things",
            // Question fillers
            "know", "understand", "learn", "tell", "explain", "wondering"
    );

    /**
     * Check if a token is a stop word
     */
    private boolean isStopWord(String token) {
        return CUSTOM_STOP_WORDS.contains(token.toLowerCase());
    }

    /**
     * Add a group of synonyms where all words map to each other
     */
    private void addSynonymGroup(SynonymMap.Builder builder, String... words) {
        if (words.length < 2) return;

        // Map all words to the first word (canonical form)
        CharsRef canonical = new CharsRef(words[0]);
        for (int i = 1; i < words.length; i++) {
            builder.add(new CharsRef(words[i]), canonical, true);
        }
    }

    /**
     * Create a custom analyzer for query normalization
     */
    private Analyzer createQueryAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                // Tokenizer: splits text into tokens
                StandardTokenizer tokenizer = new StandardTokenizer();

                // Filter chain
                TokenStream stream = tokenizer;

                // Lowercase
                stream = new LowerCaseFilter(stream);

                // ASCII folding (café → cafe, naïve → naive)
                stream = new ASCIIFoldingFilter(stream);

                // Stop words (English)
                stream = new StopFilter(stream, EnglishAnalyzer.ENGLISH_STOP_WORDS_SET);

                // Synonyms (must come before stemming)
                if (synonymMap != null) {
                    stream = new SynonymGraphFilter(stream, synonymMap, true);
                }

                // Porter stemmer (running → run, dashboards → dashboard)
                stream = new PorterStemFilter(stream);

                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }

    /**
     * Normalize a query using Lucene's analysis pipeline
     * Returns a space-separated string of normalized tokens
     */
    public String normalize(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        Set<String> tokens = analyzeToTokens(query);

        // Sort for consistent cache keys
        return String.join(" ", new TreeSet<>(tokens));
    }

    /**
     * Tokenize text into a set of normalized tokens using Lucene
     */
    public Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return analyzeToTokens(text);
    }

    /**
     * Use Lucene analyzer to extract normalized tokens
     */
    private Set<String> analyzeToTokens(String text) {
        Set<String> tokens = new HashSet<>();

        try (TokenStream stream = queryAnalyzer.tokenStream("query", new StringReader(text))) {
            CharTermAttribute termAttr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();

            while (stream.incrementToken()) {
                String token = termAttr.toString();

                // Filter out very short tokens (likely noise)
                if (token.length() <= 1) {
                    continue;
                }

                // Filter out custom stop words (these often dominate questions)
                if (isStopWord(token)) {
                    continue;
                }

                tokens.add(token);
            }
            stream.end();
        } catch (IOException e) {
            logger.warn("Error analyzing text: {}", e.getMessage());
            // Fallback: simple split
            for (String word : text.toLowerCase().split("\\s+")) {
                if (word.length() <= 1) {
                    continue;
                }
                if (isStopWord(word)) {
                    continue;
                }
                tokens.add(word);
            }
        }

        return tokens;
    }

    /**
     * Calculate Jaccard similarity between two queries
     * @return Similarity score between 0.0 and 1.0
     */
    public double calculateSimilarity(String query1, String query2) {
        Set<String> tokens1 = tokenize(query1);
        Set<String> tokens2 = tokenize(query2);
        return calculateJaccardSimilarity(tokens1, tokens2);
    }

    /**
     * Calculate Jaccard similarity between two token sets
     * J(A,B) = |A ∩ B| / |A ∪ B|
     */
    public double calculateJaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1.isEmpty() || set2.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        return (double) intersection.size() / union.size();
    }

    /**
     * Generate a cache key from a query
     * Uses hash of normalized query for consistent key length
     */
    public String generateCacheKey(String query) {
        String normalized = normalize(query);
        // Use a better hash for cache keys
        int hash = normalized.hashCode();
        return String.format("%08x", hash);
    }

    /**
     * Check if two queries are semantically similar
     */
    public boolean isSimilar(String query1, String query2, double threshold) {
        return calculateSimilarity(query1, query2) >= threshold;
    }

    /**
     * Get the set of tokens for debugging/logging
     */
    public Set<String> getTokensForDebug(String query) {
        return tokenize(query);
    }
}

