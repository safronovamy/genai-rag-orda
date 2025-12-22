package com.example.rag.evaluation;

import com.example.rag.RagApplication;
import com.example.rag.ingest.EmbeddingClient;
import com.example.rag.ingest.QdrantService;
import com.example.rag.ingest.SearchResult;
import com.example.rag.llm.LlmClient;
import com.example.rag.retrieval.Bm25Service;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EvaluationRunner
 *
 * Runs retrieval evaluation against a fixed golden set (evaluation/questions.jsonl).
 *
 * Supported modes:
 *  - baseline
 *  - hyde
 *  - hyde_stepback_fallback
 *  - hybrid
 *  - hyde_hybrid
 *  - hyde_hybrid_stepback_fallback
 *
 * Metrics:
 *  - Recall@3, Recall@5
 *  - Hit@3, Hit@5
 *  - RulePresence@3
 *  - ProductPresence@3
 *  - MSSRecall@3
 */
public class EvaluationRunner {

    private static final String DEFAULT_MODE = "baseline";
    private static final Set<String> ALLOWED_MODES = Set.of(
            "baseline",
            "hyde",
            "hyde_stepback_fallback",
            "hybrid",
            "hyde_hybrid",
            "hyde_hybrid_stepback_fallback"
    );

    private static final String QUESTIONS_RESOURCE = "evaluation/questions.jsonl";
    private static final String COLLECTION_NAME = "skincare_box";

    private static final int TOP_K = 5;

    // Hybrid (Dense + BM25) candidate pools
    private static final int DENSE_TOP_N = 25;
    private static final int BM25_TOP_N = 25;

    // RRF parameters
    private static final int RRF_K = 60;
    private static final double DENSE_WEIGHT = 1.0;
    private static final double BM25_WEIGHT = 1.25;
    private final ExecutorService executor = Executors.newFixedThreadPool(6);

    public static void main(String[] args) throws Exception {
      //new EvaluationRunner().run();
        EvaluationReport baselineReport = doReport(new String[]{"--mode=baseline"});
        EvaluationReport hydeReport = doReport(new String[]{"--mode=hyde"});
        EvaluationReport hydeStepBackReport = doReport(new String[]{"--mode=hyde_stepback_fallback"});

        printLog(baselineReport, hydeReport, hydeStepBackReport);
    }

    private static void printLog(EvaluationReport baselineReport, EvaluationReport hydeReport, EvaluationReport hydeStepBackReport) {
        System.out.println("\n=== RAG Evaluation summary ===");
        System.out.printf("Recall@3          : %s\t%s\t%s\n", baselineReport.recallAt3, hydeReport.recallAt3, hydeStepBackReport.recallAt3);
        System.out.printf("Recall@5          : %s\t%s\t%s\n", baselineReport.recallAt5, hydeReport.recallAt5, hydeStepBackReport.recallAt5);
        System.out.printf("Hit@3             : %s\t%s\t%s\n", baselineReport.hitAt3, hydeReport.hitAt3, hydeStepBackReport.hitAt3);
        System.out.printf("Hit@5             : %s\t%s\t%s\n", baselineReport.hitAt5, hydeReport.hitAt5, hydeStepBackReport.hitAt5);
        System.out.printf("RulePresence@3   : %s\t%s\t%s\n", baselineReport.rulePresenceAt3, hydeReport.rulePresenceAt3, hydeStepBackReport.rulePresenceAt3);
        System.out.printf("ProductPresence@3   : %s\t%s\t%s\n", baselineReport.productPresenceAt3, hydeReport.productPresenceAt3, hydeStepBackReport.productPresenceAt3);
    }


    public void run() {
        CompletableFuture<EvaluationReport> fa = CompletableFuture.supplyAsync(() -> doReportNoExc(new String[]{"--mode=baseline"}), executor);
        CompletableFuture<EvaluationReport> fb = CompletableFuture.supplyAsync(() -> doReportNoExc(new String[]{"--mode=hyde"}), executor);
        CompletableFuture<EvaluationReport> fc = CompletableFuture.supplyAsync(() -> doReportNoExc(new String[]{"--mode=hyde_stepback_fallback"}), executor);

        CompletableFuture<Void> all = CompletableFuture.allOf(fa, fb, fc);

        try {
            all.join();

            EvaluationReport baselineReport = fa.join();
            EvaluationReport hydeReport = fb.join();
            EvaluationReport hydeStepBackReport = fc.join();

            printLog(baselineReport, hydeReport, hydeStepBackReport);


        } catch (CompletionException ex) {
            Throwable t = ex;
            while (t.getCause() != null) t = t.getCause();
            t.printStackTrace();
        } finally {
            executor.shutdown();
        }
    }


    private static EvaluationReport doReportNoExc(String[] args)  {
        try {
            return doReport(args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private static EvaluationReport doReport(String[] args) throws Exception {
        String mode = resolveMode(args);

        ConfigurableApplicationContext context = new SpringApplicationBuilder(RagApplication.class)
                .web(WebApplicationType.NONE)
                .run();

        try {
            EmbeddingClient embeddingClient = context.getBean(EmbeddingClient.class);
            QdrantService qdrantService = context.getBean(QdrantService.class);

            // LLM required for HyDE and Step-Back.
            LlmClient llmClient = null;
            if (modeUsesHyde(mode) || modeUsesStepBack(mode)) {
                llmClient = context.getBean(LlmClient.class);
            }

            // BM25 required for hybrid modes (including "hybrid")
            Bm25Service bm25Service = null;
            if (modeUsesHybrid(mode)) {
                bm25Service = new Bm25Service();
                bm25Service.buildIndexFromClasspath(Bm25Service.DEFAULT_DATASET_RESOURCE);
            }

            ObjectMapper mapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            List<EvalQuestion> questions = loadQuestions(mapper);

            int total = questions.size();

            int hitAt3Count = 0;
            int hitAt5Count = 0;
            double recallAt3Sum = 0.0;
            double recallAt5Sum = 0.0;

            // Engineering metrics
            int ruleQuestionsCount = 0;
            int rulePresentAt3Count = 0;

            int productQuestionsCount = 0;
            int productPresentAt3Count = 0;

            int mssQuestionsCount = 0;
            double mssRecallAt3Sum = 0.0;

            List<QuestionResult> perQuestion = new ArrayList<>(questions.size());

            for (EvalQuestion q : questions) {
                String query = safeTrim(q.getQuery());

                // Dense retrieval text selection:
                // - baseline/hybrid -> query
                // - hyde* -> prefer precomputed denseRetrievalText from dataset, else generate via LLM
                String denseRetrievalText = chooseDenseRetrievalText(mode, q, llmClient, query);

                List<SearchResult> results;
                if (modeUsesHybrid(mode)) {
                    results = retrieveHybridRrf(embeddingClient, qdrantService, bm25Service, query, denseRetrievalText);
                } else {
                    results = retrieveDense(embeddingClient, qdrantService, denseRetrievalText);
                }

                if (modeUsesStepBack(mode)) {
                    results = applyStepBackFallback(embeddingClient, qdrantService, llmClient, query, results);
                }

                List<String> retrievedIds = extractDocIds(results);
                List<String> relevantIds = q.getRelevantDocIds();

                // Hit/Recall
                boolean hit3 = hitAtK(retrievedIds, relevantIds, 3);
                boolean hit5 = hitAtK(retrievedIds, relevantIds, 5);
                double recall3 = recallAtK(retrievedIds, relevantIds, 3);
                double recall5 = recallAtK(retrievedIds, relevantIds, 5);

                if (hit3) hitAt3Count++;
                if (hit5) hitAt5Count++;
                recallAt3Sum += recall3;
                recallAt5Sum += recall5;

                // RulePresence@3 (only for "rule" questions)
                boolean isRuleQuestion = relevantIds.stream().anyMatch(id -> id != null && id.startsWith("routine_"));
                boolean rulePresentAt3 = false;
                if (isRuleQuestion) {
                    ruleQuestionsCount++;
                    rulePresentAt3 = hasTypeInTopK(results, 3, "routine");
                    if (rulePresentAt3) rulePresentAt3Count++;
                }

                // ProductPresence@3 (only for "product" questions)
                boolean isProductQuestion = relevantIds.stream().anyMatch(id -> id != null && id.startsWith("product_"));
                boolean productPresentAt3 = false;
                if (isProductQuestion) {
                    productQuestionsCount++;
                    productPresentAt3 = hasTypeInTopK(results, 3, "product");
                    if (productPresentAt3) productPresentAt3Count++;
                }

                // MSSRecall@3 (average over questions where MSS is defined)
                List<String> mssIds = q.getMssDocIds();
                double mssRecallAt3ForQ = 0.0;
                if (mssIds != null && !mssIds.isEmpty()) {
                    mssQuestionsCount++;
                    mssRecallAt3ForQ = recallAtK(retrievedIds, mssIds, 3);
                    mssRecallAt3Sum += mssRecallAt3ForQ;
                }

                perQuestion.add(new QuestionResult(
                        q.getId(),
                        query,
                        denseRetrievalText,
                        relevantIds,
                        mssIds,
                        retrievedIds,
                        hit3,
                        hit5,
                        recall3,
                        recall5,
                        rulePresentAt3,
                        productPresentAt3,
                        mssRecallAt3ForQ
                ));
            }

            double hitAt3 = total == 0 ? 0.0 : (double) hitAt3Count / total;
            double hitAt5 = total == 0 ? 0.0 : (double) hitAt5Count / total;
            double recallAt3 = total == 0 ? 0.0 : recallAt3Sum / total;
            double recallAt5 = total == 0 ? 0.0 : recallAt5Sum / total;

            double rulePresenceAt3 = ruleQuestionsCount == 0 ? 0.0 : (double) rulePresentAt3Count / ruleQuestionsCount;
            double productPresenceAt3 = productQuestionsCount == 0 ? 0.0 : (double) productPresentAt3Count / productQuestionsCount;
            double mssRecallAt3 = mssQuestionsCount == 0 ? 0.0 : mssRecallAt3Sum / mssQuestionsCount;

            EvaluationReport report = new EvaluationReport(
                    mode,
                    total,
                    hitAt3,
                    hitAt5,
                    recallAt3,
                    recallAt5,
                    rulePresenceAt3,
                    productPresenceAt3,
                    mssRecallAt3,
                    ruleQuestionsCount,
                    productQuestionsCount,
                    mssQuestionsCount,
                    perQuestion
            );

            String fileName = "evaluation_report_" + mode + ".json";
            Path out = Path.of(fileName);
            mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);

            System.out.println("=== RAG Evaluation finished ===");
            System.out.println("Mode              : " + mode);
            System.out.println("Total questions   : " + total);
            System.out.println("Recall@3          : " + recallAt3);
            System.out.println("Recall@5          : " + recallAt5);
            System.out.println("Hit@3             : " + hitAt3);
            System.out.println("Hit@5             : " + hitAt5);
            System.out.println("RulePresence@3    : " + rulePresenceAt3 + " (rule questions: " + ruleQuestionsCount + ")");
            System.out.println("ProductPresence@3 : " + productPresenceAt3 + " (product questions: " + productQuestionsCount + ")");
            System.out.println("MSSRecall@3       : " + mssRecallAt3 + " (mss questions: " + mssQuestionsCount + ")");
            System.out.println("Report saved to   : " + out.toAbsolutePath());

            return report;

        } finally {
            context.close();
        }
    }

    private static String chooseDenseRetrievalText(String mode, EvalQuestion q, LlmClient llmClient, String query) throws Exception {
        // baseline and hybrid use the raw query as dense text (no HyDE)
        if ("baseline".equalsIgnoreCase(mode) || "hybrid".equalsIgnoreCase(mode)) {
            return query;
        }

        // Prefer dataset-provided denseRetrievalText (if present).
        String fromDataset = firstNonBlank(q.getDenseRetrievalText(), q.getRetrievalText());
        if (!fromDataset.isBlank()) {
            return fromDataset;
        }

        // Otherwise generate HyDE for hyde* modes.
        if (modeUsesHyde(mode)) {
            if (llmClient == null) {
                throw new IllegalStateException("LlmClient is required for mode=" + mode);
            }
            String hyde = llmClient.generateHydeText(query);
            return firstNonBlank(hyde, query);
        }

        return query;
    }

    private static List<SearchResult> retrieveDense(EmbeddingClient embeddingClient,
                                                    QdrantService qdrantService,
                                                    String denseRetrievalText) throws Exception {
        List<Double> vec = embeddingClient.embed(denseRetrievalText);
        return qdrantService.search(COLLECTION_NAME, vec, TOP_K);
    }

    private static List<SearchResult> retrieveHybridRrf(EmbeddingClient embeddingClient,
                                                        QdrantService qdrantService,
                                                        Bm25Service bm25Service,
                                                        String userQuery,
                                                        String denseRetrievalText) throws Exception {

        List<Double> denseVec = embeddingClient.embed(denseRetrievalText);
        List<SearchResult> denseCandidates = qdrantService.search(COLLECTION_NAME, denseVec, DENSE_TOP_N);

        List<Bm25Service.Bm25Hit> bm25Hits = bm25Service.search(userQuery, BM25_TOP_N);
        List<SearchResult> bm25Candidates = bm25HitsToSearchResults(bm25Hits);

        List<SearchResult> fused = fuseByRrf(denseCandidates, bm25Candidates, RRF_K, DENSE_WEIGHT, BM25_WEIGHT);
        return fused.size() <= TOP_K ? fused : fused.subList(0, TOP_K);
    }

    /**
     * Step-Back fallback: patch missing routine/ingredient docs without re-ranking top results.
     */
    private static List<SearchResult> applyStepBackFallback(EmbeddingClient embeddingClient,
                                                            QdrantService qdrantService,
                                                            LlmClient llmClient,
                                                            String userQuestion,
                                                            List<SearchResult> currentTopK) throws Exception {

        if (llmClient == null) {
            throw new IllegalStateException("LlmClient is required for Step-Back fallback");
        }

        boolean hasRoutine = hasTypeInTopK(currentTopK, TOP_K, "routine");
        boolean hasIngredient = hasTypeInTopK(currentTopK, TOP_K, "ingredient");

        boolean looksLikeRuleQuestion = looksLikeRuleQuestion(userQuestion);
        boolean looksLikeActivesQuestion = looksLikeActivesQuestion(userQuestion);

        boolean needRoutine = looksLikeRuleQuestion && !hasRoutine;
        boolean needIngredient = looksLikeActivesQuestion && !hasIngredient;

        if (!needRoutine && !needIngredient) {
            return currentTopK;
        }

        String stepBackText = llmClient.generateStepBackText(userQuestion);
        if (stepBackText == null || stepBackText.isBlank()) {
            return currentTopK;
        }

        List<Double> stepVec = embeddingClient.embed(stepBackText);
        List<SearchResult> stepHits = qdrantService.search(COLLECTION_NAME, stepVec, 5);

        List<SearchResult> patches = new ArrayList<>();
        for (SearchResult r : stepHits) {
            String type = safeType(r);
            if (needRoutine && "routine".equals(type)) patches.add(r);
            else if (needIngredient && "ingredient".equals(type)) patches.add(r);
        }

        LinkedHashMap<String, SearchResult> merged = new LinkedHashMap<>();
        for (SearchResult r : currentTopK) {
            String id = safeDocId(r);
            if (!id.isBlank()) merged.put(id, r);
        }

        int maxAdd = 2;
        int added = 0;
        for (SearchResult r : patches) {
            if (added >= maxAdd) break;
            String id = safeDocId(r);
            if (id.isBlank() || merged.containsKey(id)) continue;
            merged.put(id, r);
            added++;
        }

        List<SearchResult> out = new ArrayList<>(merged.values());
        return out.size() <= TOP_K ? out : out.subList(0, TOP_K);
    }

    private static List<SearchResult> bm25HitsToSearchResults(List<Bm25Service.Bm25Hit> hits) {
        if (hits == null || hits.isEmpty()) return List.of();

        List<SearchResult> out = new ArrayList<>(hits.size());
        for (Bm25Service.Bm25Hit h : hits) {
            if (h == null) continue;

            Map<String, Object> payload = new HashMap<>();
            payload.put("doc_id", h.docId);
            payload.put("type", h.type);
            payload.put("source", "bm25");

            // IMPORTANT: SearchResult has only (double score, Map payload) constructor
            out.add(new SearchResult((double) h.score, payload));
        }
        return out;
    }

    private static List<SearchResult> fuseByRrf(List<SearchResult> a,
                                                List<SearchResult> b,
                                                int rrfK,
                                                double aWeight,
                                                double bWeight) {

        Map<String, Map<String, Object>> payloadById = new HashMap<>();
        Map<String, Double> scoreById = new HashMap<>();

        accumulateRrf(a, rrfK, aWeight, payloadById, scoreById);
        accumulateRrf(b, rrfK, bWeight, payloadById, scoreById);

        List<Map.Entry<String, Double>> scored = new ArrayList<>(scoreById.entrySet());
        scored.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));

        List<SearchResult> out = new ArrayList<>(scored.size());
        for (Map.Entry<String, Double> e : scored) {
            Map<String, Object> payload = payloadById.get(e.getKey());
            out.add(new SearchResult(e.getValue(), payload));
        }
        return out;
    }

    private static void accumulateRrf(List<SearchResult> ranked,
                                      int rrfK,
                                      double weight,
                                      Map<String, Map<String, Object>> payloadById,
                                      Map<String, Double> scoreById) {
        if (ranked == null) return;

        int rank = 1;
        for (SearchResult r : ranked) {
            String id = safeDocId(r);
            if (id.isBlank()) {
                rank++;
                continue;
            }

            payloadById.putIfAbsent(id, r.getPayload());
            double add = weight * (1.0 / (rrfK + rank));
            scoreById.merge(id, add, Double::sum);
            rank++;
        }
    }

    private static boolean modeUsesHyde(String mode) {
        return "hyde".equalsIgnoreCase(mode)
                || "hyde_stepback_fallback".equalsIgnoreCase(mode)
                || "hyde_hybrid".equalsIgnoreCase(mode)
                || "hyde_hybrid_stepback_fallback".equalsIgnoreCase(mode);
    }

    private static boolean modeUsesHybrid(String mode) {
        return "hybrid".equalsIgnoreCase(mode)
                || "hyde_hybrid".equalsIgnoreCase(mode)
                || "hyde_hybrid_stepback_fallback".equalsIgnoreCase(mode);
    }

    private static boolean modeUsesStepBack(String mode) {
        return "hyde_stepback_fallback".equalsIgnoreCase(mode)
                || "hyde_hybrid_stepback_fallback".equalsIgnoreCase(mode);
    }

    private static List<EvalQuestion> loadQuestions(ObjectMapper mapper) throws IOException {
        ClassPathResource res = new ClassPathResource(QUESTIONS_RESOURCE);
        if (!res.exists()) {
            throw new IllegalStateException("Questions resource not found: " + QUESTIONS_RESOURCE);
        }

        List<EvalQuestion> out = new ArrayList<>();

        try (InputStream is = res.getInputStream()) {
            String jsonl = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            try (BufferedReader br = new BufferedReader(new StringReader(jsonl))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (t.isBlank()) continue;

                    EvalQuestion q = mapper.readValue(t, EvalQuestion.class);
                    out.add(q);
                }
            }
        }

        return out;
    }

    private static List<String> extractDocIds(List<SearchResult> results) {
        if (results == null) return List.of();
        List<String> out = new ArrayList<>(results.size());
        for (SearchResult r : results) {
            String id = safeDocId(r);
            if (!id.isBlank()) out.add(id);
        }
        return out;
    }

    private static String safeDocId(SearchResult r) {
        if (r == null || r.getPayload() == null) return "";
        Object id = r.getPayload().get("doc_id");
        return id == null ? "" : id.toString().trim();
    }

    private static String safeType(SearchResult r) {
        if (r == null || r.getPayload() == null) return "";
        Object t = r.getPayload().get("type");
        return t == null ? "" : t.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasTypeInTopK(List<SearchResult> results, int k, String expectedType) {
        if (results == null || results.isEmpty()) return false;
        int limit = Math.min(k, results.size());
        for (int i = 0; i < limit; i++) {
            if (expectedType.equals(safeType(results.get(i)))) return true;
        }
        return false;
    }

    private static String resolveMode(String[] args) {
        String mode = DEFAULT_MODE;
        if (args != null) {
            for (String a : args) {
                if (a != null && a.startsWith("--mode=")) {
                    mode = a.substring("--mode=".length()).trim();
                }
            }
        }
        if (!ALLOWED_MODES.contains(mode)) {
            throw new IllegalArgumentException("Unsupported mode: " + mode + " (allowed: " + ALLOWED_MODES + ")");
        }
        return mode;
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) return "";
        for (String c : candidates) {
            if (c != null && !c.trim().isBlank()) return c.trim();
        }
        return "";
    }

    // Lightweight heuristics to avoid unnecessary Step-Back calls.
    private static boolean looksLikeRuleQuestion(String q) {
        String s = q == null ? "" : q.toLowerCase(Locale.ROOT);
        return s.contains("how often")
                || s.contains("frequency")
                || s.contains("order")
                || s.contains("routine")
                || s.contains("steps")
                || s.contains("combine")
                || s.contains("together")
                || s.contains("avoid")
                || s.contains("safe")
                || s.contains("should i");
    }

    private static boolean looksLikeActivesQuestion(String q) {
        String s = q == null ? "" : q.toLowerCase(Locale.ROOT);
        return s.contains("retinol")
                || s.contains("retinoid")
                || s.contains("vitamin c")
                || s.contains("aha")
                || s.contains("bha")
                || s.contains("pha")
                || s.contains("niacinamide")
                || s.contains("exfol")
                || s.contains("peel");
    }

    private static double recallAtK(List<String> retrievedIds, List<String> relevantIds, int k) {
        if (relevantIds == null || relevantIds.isEmpty()) return 0.0;
        Set<String> rel = new HashSet<>(relevantIds);
        int limit = Math.min(k, retrievedIds.size());
        int hits = 0;
        for (int i = 0; i < limit; i++) {
            if (rel.contains(retrievedIds.get(i))) hits++;
        }
        return (double) hits / rel.size();
    }

    private static boolean hitAtK(List<String> retrievedIds, List<String> relevantIds, int k) {
        if (relevantIds == null || relevantIds.isEmpty()) return false;
        int limit = Math.min(k, retrievedIds.size());
        for (int i = 0; i < limit; i++) {
            if (relevantIds.contains(retrievedIds.get(i))) return true;
        }
        return false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvalQuestion {
        private String id;
        private String query;

        // Optional: for precomputed retrieval texts in the dataset
        private String denseRetrievalText;
        private String retrievalText;

        // IMPORTANT: keep snake_case to match questions.jsonl
        private List<String> relevant_doc_ids;
        private List<String> mss_doc_ids;

        public String getId() { return id; }
        public String getQuery() { return query; }

        public String getDenseRetrievalText() { return denseRetrievalText; }
        public String getRetrievalText() { return retrievalText; }

        public List<String> getRelevantDocIds() {
            return relevant_doc_ids == null ? List.of() : relevant_doc_ids;
        }

        public List<String> getMssDocIds() {
            return mss_doc_ids == null ? List.of() : mss_doc_ids;
        }

        // setters for Jackson
        public void setId(String id) { this.id = id; }
        public void setQuery(String query) { this.query = query; }

        public void setDenseRetrievalText(String denseRetrievalText) { this.denseRetrievalText = denseRetrievalText; }
        public void setRetrievalText(String retrievalText) { this.retrievalText = retrievalText; }

        public void setRelevant_doc_ids(List<String> relevant_doc_ids) { this.relevant_doc_ids = relevant_doc_ids; }
        public void setMss_doc_ids(List<String> mss_doc_ids) { this.mss_doc_ids = mss_doc_ids; }
    }


    public record QuestionResult(
            String id,
            String query,
            String denseRetrievalText,
            List<String> relevantDocIds,
            List<String> mssDocIds,
            List<String> retrievedDocIds,
            boolean hitAt3,
            boolean hitAt5,
            double recallAt3,
            double recallAt5,
            boolean rulePresenceAt3,
            boolean productPresenceAt3,
            double mssRecallAt3
    ) {}

    public record EvaluationReport(
            String mode,
            int totalQuestions,
            double hitAt3,
            double hitAt5,
            double recallAt3,
            double recallAt5,
            double rulePresenceAt3,
            double productPresenceAt3,
            double mssRecallAt3,
            int ruleQuestionsCount,
            int productQuestionsCount,
            int mssQuestionsCount,
            List<QuestionResult> perQuestion
    ) {}
}
