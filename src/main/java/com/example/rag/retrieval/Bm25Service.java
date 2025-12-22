package com.example.rag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.springframework.core.io.ClassPathResource;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * In-memory BM25 (Lucene) service.
 * Builds an index from classpath resource skincare_dataset.json (same source as Qdrant ingestion).
 * Designed for evaluation and lightweight hybrid retrieval experiments.
 */
@Service
public class Bm25Service {

    public static final String DEFAULT_DATASET_RESOURCE = "skincare_dataset.json";

    // Lucene field names
    private static final String F_DOC_ID = "doc_id";
    private static final String F_TYPE = "type";
    private static final String F_TITLE = "title";
    private static final String F_TEXT = "text";
    private static final String F_INGREDIENTS = "ingredients";
    private static final String F_ABOUT = "about";
    private static final String F_HOW_TO_USE = "how_to_use";
    private static final String F_ALL = "all";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Analyzer analyzer = new EnglishAnalyzer();
    private final Directory directory = new ByteBuffersDirectory();

    private IndexSearcher searcher;

    /**
     * Build BM25 index from dataset resource.
     */
    public void buildIndexFromClasspath(String datasetResourcePath) throws Exception {
        ClassPathResource res = new ClassPathResource(datasetResourcePath);
        if (!res.exists()) {
            throw new IllegalStateException("Dataset resource not found: " + datasetResourcePath);
        }

        try (InputStream is = res.getInputStream()) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = mapper.readTree(json);
            if (!root.isArray()) {
                throw new IllegalStateException("Dataset JSON must be an array: " + datasetResourcePath);
            }

            IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
            cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

            try (IndexWriter writer = new IndexWriter(directory, cfg)) {
                for (JsonNode n : root) {
                    String docId = text(n, "id"); // dataset uses "id"
                    if (docId.isBlank()) continue;

                    String type = text(n, "type");
                    String title = firstNonBlank(
                            text(n, "title"),
                            text(n, "name")
                    );

                    // These fields exist in your payload ingestion runner
                    String text = text(n, "text");
                    String about = text(n, "about");
                    String ingredients = text(n, "ingredients");
                    String howToUse = firstNonBlank(
                            text(n, "how_to_use"),
                            text(n, "howToUse") // tolerate alternative naming
                    );

                    // Compose a single catch-all field (useful for robust matching)
                    String all = joinNonBlank(
                            title,
                            type,
                            about,
                            text,
                            ingredients,
                            howToUse
                    );

                    Document d = new Document();

                    // IDs and metadata
                    d.add(new StringField(F_DOC_ID, docId, Field.Store.YES));
                    d.add(new StringField(F_TYPE, emptyAsUnknown(type), Field.Store.YES));

                    // Weighted searchable fields
                    d.add(new TextField(F_TITLE, safe(title), Field.Store.NO));
                    d.add(new TextField(F_TEXT, safe(text), Field.Store.NO));
                    d.add(new TextField(F_ABOUT, safe(about), Field.Store.NO));
                    d.add(new TextField(F_INGREDIENTS, safe(ingredients), Field.Store.NO));
                    d.add(new TextField(F_HOW_TO_USE, safe(howToUse), Field.Store.NO));

                    // Catch-all
                    d.add(new TextField(F_ALL, safe(all), Field.Store.NO));

                    writer.addDocument(d);
                }
                writer.commit();
            }
        }

        // Prepare searcher
        DirectoryReader reader = DirectoryReader.open(directory);
        this.searcher = new IndexSearcher(reader);
    }

    /**
     * BM25 search. Returns list of doc_id with scores (Lucene BM25).
     * Query uses boosts across multiple fields.
     */
    public List<Bm25Hit> search(String queryText, int topN) throws Exception {
        ensureReady();

        String q = safe(queryText);
        if (q.isBlank()) return List.of();

        // Field boosts: title and ingredients are typically strong signals in skincare domain.
        Map<String, Float> boosts = new HashMap<>();
        boosts.put(F_TITLE, 3.0f);
        boosts.put(F_INGREDIENTS, 2.5f);
        boosts.put(F_TEXT, 1.5f);
        boosts.put(F_ABOUT, 1.2f);
        boosts.put(F_HOW_TO_USE, 1.2f);
        boosts.put(F_ALL, 1.0f);

        String[] fields = boosts.keySet().toArray(new String[0]);
        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer, boosts);
        parser.setAllowLeadingWildcard(false);

        Query luceneQuery = parser.parse(MultiFieldQueryParser.escape(q));

        TopDocs top = searcher.search(luceneQuery, topN);
        if (top.scoreDocs == null || top.scoreDocs.length == 0) return List.of();

        List<Bm25Hit> out = new ArrayList<>(top.scoreDocs.length);
        for (ScoreDoc sd : top.scoreDocs) {
            Document d = searcher.doc(sd.doc);
            String docId = d.get(F_DOC_ID);
            String type = d.get(F_TYPE);
            out.add(new Bm25Hit(docId, type, sd.score));
        }
        return out;
    }

    private void ensureReady() {
        if (searcher == null) {
            throw new IllegalStateException("BM25 index is not built. Call buildIndexFromClasspath() first.");
        }
    }

    private static String text(JsonNode n, String field) {
        if (n == null || field == null) return "";
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? "" : v.asText("").trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String emptyAsUnknown(String s) {
        String t = safe(s);
        return t.isBlank() ? "unknown" : t;
    }

    private static String firstNonBlank(String a, String b) {
        String x = safe(a);
        if (!x.isBlank()) return x;
        return safe(b);
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String t = safe(p);
            if (!t.isBlank()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(t);
            }
        }
        return sb.toString();
    }

    /**
     * BM25 hit result.
     */
    public static class Bm25Hit {
        public final String docId;
        public final String type;
        public final float score;

        public Bm25Hit(String docId, String type, float score) {
            this.docId = docId;
            this.type = type;
            this.score = score;
        }
    }

  /*  @PostConstruct
    public void init() {
        try {
            buildIndexFromClasspath(DEFAULT_DATASET_RESOURCE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build BM25 index", e);
        }
    }*/

}
