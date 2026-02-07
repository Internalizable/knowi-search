package me.internalizable.knowisearch.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Service
public class KnowiCrawlerService {

    private static final Logger logger = LoggerFactory.getLogger(KnowiCrawlerService.class);
    private static final Pattern DOC_URL_PATTERN = Pattern.compile("^https://www\\.knowi\\.com/docs/.+\\.html$");

    @Value("${knowi.docs.base-url}")
    private String docsBaseUrl;

    @Value("${knowi.docs.max-pages}")
    private int maxPages;

    @Value("${knowi.docs.chunk-size}")
    private int chunkSize;

    @Value("${knowi.docs.chunk-overlap}")
    private int chunkOverlap;

    /**
     * Represents a crawled page
     */
    public record CrawledPage(String url, String title, String text, List<String> headings) {}

    /**
     * Represents a chunk of text from a page
     */
    public record DocChunk(String url, String title, String chunk, int chunkIndex) {}

    /**
     * Crawl Knowi documentation pages with parallel fetching
     */
    public List<CrawledPage> crawl() {
        String startUrl = normalizeUrl(docsBaseUrl.endsWith("/") ? docsBaseUrl : docsBaseUrl + "/");

        Set<String> visited = ConcurrentHashMap.newKeySet();
        Set<String> toVisit = ConcurrentHashMap.newKeySet();
        List<CrawledPage> pages = Collections.synchronizedList(new ArrayList<>());

        toVisit.add(startUrl);

        try {
            Set<String> menuUrls = extractMenuUrls(startUrl);
            toVisit.addAll(menuUrls);
            logger.info("Seeded {} URLs from left menu", menuUrls.size());
        } catch (Exception e) {
            logger.warn("Menu URL extraction failed, continuing with startUrl only: {}", e.getMessage());
        }

        logger.info("Starting parallel crawl from: {}", startUrl);

        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            while (!toVisit.isEmpty() && visited.size() < maxPages) {
                List<String> batch = new ArrayList<>();
                Iterator<String> it = toVisit.iterator();
                while (it.hasNext() && batch.size() < 10) {
                    String url = it.next();
                    it.remove();
                    if (visited.add(url)) {
                        batch.add(url);
                    }
                }

                if (batch.isEmpty()) break;

                List<Future<CrawledPage>> futures = new ArrayList<>();
                for (String url : batch) {
                    futures.add(executor.submit(() -> crawlPage(url, toVisit, visited)));
                }

                for (Future<CrawledPage> future : futures) {
                    try {
                        CrawledPage page = future.get(30, TimeUnit.SECONDS);
                        if (page != null) {
                            pages.add(page);
                            logger.info("Crawled page {}: {} ({} chars)",
                                pages.size(), page.title(), page.text().length());
                        }
                    } catch (Exception e) {
                        logger.debug("Page fetch failed: {}", e.getMessage());
                    }
                }
            }
        }

        logger.info("Crawl complete. Total pages: {}", pages.size());
        return pages;
    }

    private CrawledPage crawlPage(String url, Set<String> toVisit, Set<String> visited) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("KnowiDocsBot/1.0")
                    .timeout(15000)
                    .followRedirects(true)
                    .get();

            String title = doc.title() != null ? doc.title() : "";
            List<String> headings = extractHeadings(doc);
            String mainText = extractMainContent(doc);

            if (!mainText.isBlank() && mainText.length() > 100) {
                discoverLinks(doc, toVisit, visited);
                return new CrawledPage(url, title, mainText, headings);
            }
        } catch (Exception e) {
            logger.debug("Failed to crawl {}: {}", url, e.getMessage());
        }
        return null;
    }

    /**
     * Extract headings for better context
     */
    private List<String> extractHeadings(Document doc) {
        List<String> headings = new ArrayList<>();
        Elements h2s = doc.select("#doc-container h2, #doc-container h3");
        for (Element h : h2s) {
            String text = h.text().trim();
            if (!text.isBlank()) {
                headings.add(text);
            }
        }
        return headings;
    }

    /**
     * Extract main content from a Knowi docs page
     */
    private String extractMainContent(Document doc) {
        Element docContainer = doc.selectFirst("#doc-container");
        Element content = docContainer != null ? docContainer : doc.body();

        content = content.clone();

        content.select("script, style, nav, header, footer, .menu-items-ul, #toc, .nav-bar, .popup-box, form").remove();

        String text = content.text();
        text = text.replaceAll("\\s+", " ").trim();

        return text;
    }

    /**
     * Discover more documentation links from a page
     */
    private void discoverLinks(Document doc, Set<String> toVisit, Set<String> visited) {
        Elements links = doc.select("a[href]");

        for (Element link : links) {
            String href = link.attr("abs:href");
            if (href.isBlank()) {
                continue;
            }

            href = normalizeUrl(href);

            if (DOC_URL_PATTERN.matcher(href).matches() && !visited.contains(href)) {
                toVisit.add(href);
            }
        }
    }

    /**
     * Normalize URL by removing fragments
     */
    private String normalizeUrl(String url) {
        try {
            URI uri = URI.create(url);
            URI cleaned = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null);
            return cleaned.toString();
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Split pages into overlapping chunks with heading context
     */
    public List<DocChunk> chunkPages(List<CrawledPage> pages) {
        List<DocChunk> chunks = new ArrayList<>();

        for (CrawledPage page : pages) {
            String enrichedTitle = page.title();
            if (page.headings() != null && !page.headings().isEmpty()) {
                enrichedTitle = page.title() + " - " + String.join(", ", page.headings().subList(0, Math.min(3, page.headings().size())));
            }

            List<String> textChunks = splitIntoChunks(page.text(), chunkSize, chunkOverlap);

            for (int i = 0; i < textChunks.size(); i++) {
                chunks.add(new DocChunk(page.url(), enrichedTitle, textChunks.get(i), i));
            }
        }

        logger.info("Created {} chunks from {} pages", chunks.size(), pages.size());
        return chunks;
    }

    /**
     * Split text into overlapping chunks, trying to break at sentence boundaries
     */
    private List<String> splitIntoChunks(String text, int maxChars, int overlap) {
        List<String> chunks = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return chunks;
        }

        int i = 0;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + maxChars);

            if (end < text.length()) {
                int sentenceEnd = findSentenceBreak(text, i + maxChars / 2, end);
                if (sentenceEnd > 0) {
                    end = sentenceEnd;
                } else {
                    int lastSpace = text.lastIndexOf(' ', end);
                    if (lastSpace > i + maxChars / 2) {
                        end = lastSpace;
                    }
                }
            }

            String chunk = text.substring(i, end).trim();
            if (!chunk.isBlank() && chunk.length() > 50) {
                chunks.add(chunk);
            }

            if (end == text.length()) {
                break;
            }

            i = Math.max(i + 1, end - overlap);
        }

        return chunks;
    }

    private int findSentenceBreak(String text, int start, int end) {
        for (int i = end; i >= start; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && i + 1 < text.length() && text.charAt(i + 1) == ' ') {
                return i + 1;
            }
        }
        return -1;
    }

    private Set<String> extractMenuUrls(String anyDocsUrl) throws IOException {
        Set<String> urls = new HashSet<>();

        Document doc = Jsoup.connect(anyDocsUrl)
                .userAgent("KnowiDocsBot/1.0")
                .timeout(15000)
                .followRedirects(true)
                .get();

        Elements links = doc.select(".menu-items-ul a[href]");

        for (Element link : links) {
            String href = link.attr("abs:href");
            if (href.isBlank()) continue;

            href = normalizeUrl(href);

            if (DOC_URL_PATTERN.matcher(href).matches()) {
                urls.add(href);
            }
        }

        return urls;
    }

}

