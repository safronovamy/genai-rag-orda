package com.example.rag.api;

import com.example.rag.ingest.EmbeddingClient;
import com.example.rag.ingest.QdrantService;
import com.example.rag.ingest.SearchResult;
import com.example.rag.llm.LlmClient;
import com.example.rag.retrieval.Bm25Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller that exposes a simple RAG endpoint.
 *
 * POST /api/ask
 * {
 *   "question": "My skin stings sometimes and I want a simple night routine.",
 *   "mode": "hyde_stepback_fallback"
 * }
 *
 * Modes are aligned with EvaluationRunner:
 *  - baseline
 *  - hyde
 *  - hyde_stepback_fallback
 *  - hyde_hybrid_rrf
 *  - hyde_hybrid_stepback_fallback
 */
@RestController
@RequestMapping("/api")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private static final String COLLECTION_NAME = "skincare_box";

    // Retrieval modes
    private static final String MODE_BASELINE = "baseline";
    private static final String MODE_HYDE = "hyde";
    private static final String MODE_HYDE_HYBRID_RRF = "hyde_hybrid_rrf";

    // New modes: Step-Back fallback
    private static final String MODE_HYDE_STEPBACK_FALLBACK = "hyde_stepback_fallback";
    private static final String MODE_HYDE_HYBRID_STEPBACK_FALLBACK = "hyde_hybrid_stepback_fallback";

    // Hybrid parameters
    private static final int DEFAULT_DENSE_TOP_N = 25;
    private static final int DEFAULT_BM25_TOP_N = 25;
    private static final int DEFAULT_RRF_K = 60;
    private static final double DEFAULT_DENSE_WEIGHT = 1.0;
    private static final double DEFAULT_BM25_WEIGHT = 1.25;

    private final EmbeddingClient embeddingClient;
    private final QdrantService qdrantService;
    private final LlmClient llmClient;
    private final Bm25Service bm25Service;

    public RagController(EmbeddingClient embeddingClient,
                         QdrantService qdrantService,
                         LlmClient llmClient,
                         Bm25Service bm25Service) {
        this.embeddingClient = embeddingClient;
        this.qdrantService = qdrantService;
        this.llmClient = llmClient;
        this.bm25Service = bm25Service;
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) throws Exception {
        String question = request.getQuestion();
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be empty");
        }

        String mode = normalizeMode(request.getMode());
        log.info("Received question (mode={}): {}", mode, question);

        int topK = 5;

        // =========================
        // 1) Build dense retrieval text (HyDE or raw)
        // =========================
        String retrievalText = question;
        if (modeUsesHyde(mode)) {
            retrievalText = llmClient.generateHydeText(question);
            if (retrievalText == null || retrievalText.isBlank()) {
                retrievalText = question;
                log.warn("HyDE generation returned empty text. Falling back to original question.");
            }
        }

        // =========================
        // 2) Retrieve candidates (dense or hybrid)
        // =========================
        List<SearchResult> hits;
        if (modeUsesHybrid(mode)) {
            hits = retrieveHybridRrf(question, retrievalText, topK);
        } else {
            hits = retrieveDense(retrievalText, topK);
        }

        // =========================
        // 3) Step-Back fallback (gap patching)
        // =========================
        if (modeUsesStepBackFallback(mode)) {
            hits = applyStepBackFallback(question, hits, topK);
        }

        if (hits == null || hits.isEmpty()) {
            return new AskResponse(
                    "Sorry, I could not find any relevant documents in the knowledge base.",
                    List.of()
            );
        }

        // =========================
        // 4) Build context for LLM
        // =========================
        String context = buildContextFromHits(hits);

        // =========================
        // 5) Compose prompts for LLM
        // =========================
        String systemPrompt = """
                You are a skincare assistant specializing in Korean multi-step routines.
                Answer in English in a clear and practical way.
                Use ONLY the provided context about products, ingredients, routines and rules.
                If the information is missing, say that it is not present in the dataset.
                Whenever possible, recommend products that exist in the dataset.
                """;

        String userPrompt = """
                User question:
                %s

                Relevant context from knowledge base:
                %s
                """.formatted(question, context);

        String answer = llmClient.askWithContext(systemPrompt, userPrompt);

        List<Map<String, Object>> sources = hits.stream()
                .map(SearchResult::getPayload)
                .filter(Objects::nonNull)
                .toList();

        return new AskResponse(answer, sources);

    }

    private List<SearchResult> retrieveDense(String retrievalText, int topK) throws Exception {
        List<Double> qVec = embeddingClient.embed(retrievalText);
        return qdrantService.search(COLLECTION_NAME, qVec, topK);
    }

    private List<SearchResult> retrieveHybridRrf(String userQuestion, String denseRetrievalText, int topK) throws Exception {
        // Dense candidates
        List<Double> denseVector = embeddingClient.embed(denseRetrievalText);
        List<SearchResult> denseCandidates = qdrantService.search(COLLECTION_NAME, denseVector, DEFAULT_DENSE_TOP_N);

        // BM25 candidates (raw user question)
        List<Bm25Service.Bm25Hit> bm25Hits = bm25Service.search(userQuestion, DEFAULT_BM25_TOP_N);
        List<SearchResult> bm25Candidates = bm25HitsToSearchResults(bm25Hits);

        List<SearchResult> fused = fuseByRrf(
                denseCandidates,
                bm25Candidates,
                DEFAULT_RRF_K,
                DEFAULT_DENSE_WEIGHT,
                DEFAULT_BM25_WEIGHT
        );

        if (fused.size() <= topK) return fused;
        return fused.subList(0, topK);
    }

    /**
     * Step-Back fallback: patch retrieval gaps (typically missing routine/ingredient docs)
     * without disrupting strong product matches from the primary retrieval (HyDE / hybrid).
     */
    private List<SearchResult> applyStepBackFallback(String userQuestion, List<SearchResult> currentTopK, int topK) throws Exception {
        boolean hasRoutine = hasTypeInTopK(currentTopK, topK, "routine");
        boolean hasIngredient = hasTypeInTopK(currentTopK, topK, "ingredient");

        boolean looksLikeRuleQuestion = looksLikeRuleQuestion(userQuestion);
        boolean looksLikeActivesQuestion = looksLikeActivesQuestion(userQuestion);

        boolean needRoutine = looksLikeRuleQuestion && !hasRoutine;
        boolean needIngredient = looksLikeActivesQuestion && !hasIngredient;

        if (!needRoutine && !needIngredient) {
            return currentTopK;
        }

        String stepBackText = llmClient.generateStepBackText(userQuestion);
        if (stepBackText == null || stepBackText.isBlank()) {
            log.warn("Step-Back generation returned empty text. Skipping fallback.");
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

        // Merge: keep current ranking, append up to 2 unique patch docs.
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
        if (out.size() > topK) {
            out = out.subList(0, topK);
        }
        return out;
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

            // IMPORTANT: SearchResult constructor is (score, payload)
            out.add(new SearchResult((double) h.score, payload));
        }
        return out;
    }

    private static List<SearchResult> fuseByRrf(
            List<SearchResult> dense,
            List<SearchResult> bm25,
            int rrfK,
            double denseWeight,
            double bm25Weight
    ) {
        Map<String, Map<String, Object>> payloadById = new HashMap<>();
        Map<String, Double> rrfScoreById = new HashMap<>();

        accumulateRrf(dense, rrfK, denseWeight, payloadById, rrfScoreById);
        accumulateRrf(bm25, rrfK, bm25Weight, payloadById, rrfScoreById);

        List<Map.Entry<String, Double>> scored = new ArrayList<>(rrfScoreById.entrySet());
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<SearchResult> out = new ArrayList<>(scored.size());
        for (Map.Entry<String, Double> e : scored) {
            String docId = e.getKey();
            Map<String, Object> payload = payloadById.get(docId);
            // IMPORTANT: SearchResult constructor is (score, payload)
            out.add(new SearchResult(e.getValue(), payload));
        }

        return out;
    }

    private static void accumulateRrf(
            List<SearchResult> ranked,
            int rrfK,
            double weight,
            Map<String, Map<String, Object>> payloadById,
            Map<String, Double> rrfScoreById
    ) {
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
            rrfScoreById.merge(id, add, Double::sum);
            rank++;
        }
    }

    private static String normalizeMode(String modeRaw) {
        if (modeRaw == null || modeRaw.isBlank()) {
            return MODE_BASELINE;
        }

        String m = modeRaw.trim().toLowerCase(Locale.ROOT);

        return switch (m) {
            case MODE_HYDE -> MODE_HYDE;
            case MODE_HYDE_STEPBACK_FALLBACK -> MODE_HYDE_STEPBACK_FALLBACK;
            case MODE_HYDE_HYBRID_RRF -> MODE_HYDE_HYBRID_RRF;
            case MODE_HYDE_HYBRID_STEPBACK_FALLBACK -> MODE_HYDE_HYBRID_STEPBACK_FALLBACK;
            default -> MODE_BASELINE;
        };
    }

    private static boolean modeUsesHyde(String mode) {
        return MODE_HYDE.equalsIgnoreCase(mode)
                || MODE_HYDE_STEPBACK_FALLBACK.equalsIgnoreCase(mode)
                || MODE_HYDE_HYBRID_RRF.equalsIgnoreCase(mode)
                || MODE_HYDE_HYBRID_STEPBACK_FALLBACK.equalsIgnoreCase(mode);
    }

    private static boolean modeUsesHybrid(String mode) {
        return MODE_HYDE_HYBRID_RRF.equalsIgnoreCase(mode)
                || MODE_HYDE_HYBRID_STEPBACK_FALLBACK.equalsIgnoreCase(mode);
    }

    private static boolean modeUsesStepBackFallback(String mode) {
        return MODE_HYDE_STEPBACK_FALLBACK.equalsIgnoreCase(mode)
                || MODE_HYDE_HYBRID_STEPBACK_FALLBACK.equalsIgnoreCase(mode);
    }

    private static boolean hasTypeInTopK(List<SearchResult> results, int k, String expectedType) {
        if (results == null || results.isEmpty()) return false;
        int limit = Math.min(k, results.size());
        for (int i = 0; i < limit; i++) {
            if (expectedType.equals(safeType(results.get(i)))) return true;
        }
        return false;
    }

    private static String safeType(SearchResult r) {
        if (r == null || r.getPayload() == null) return "";
        Object t = r.getPayload().get("type");
        return t == null ? "" : t.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static String safeDocId(SearchResult r) {
        if (r == null || r.getPayload() == null) return "";
        Object id = r.getPayload().get("doc_id");
        return id == null ? "" : id.toString().trim();
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
                || s.contains("should i")
                || s.contains("can i");
    }

    private static boolean looksLikeActivesQuestion(String q) {
        String s = q == null ? "" : q.toLowerCase(Locale.ROOT);
        return s.contains("retinol")
                || s.contains("retinal")
                || s.contains("vitamin c")
                || s.contains("aha")
                || s.contains("bha")
                || s.contains("pha")
                || s.contains("niacinamide")
                || s.contains("tranexamic")
                || s.contains("peptides")
                || s.contains("acid")
                || s.contains("exfol");
    }

    private static String buildContextFromHits(List<SearchResult> hits) {
        return hits.stream()
                .map(hit -> {
                    Map<String, Object> p = hit.getPayload();

                    Object docId = p.get("doc_id");
                    Object type = p.get("type");
                    Object title = p.get("title");
                    Object text = p.get("text");
                    Object concerns = p.get("concerns");
                    Object skinType = p.get("skin_type");
                    Object ageRange = p.get("age_range");

                    StringBuilder sb = new StringBuilder();
                    sb.append("Document: ").append(docId).append("\n");
                    sb.append("Type: ").append(type).append("\n");
                    if (title != null) {
                        sb.append("Title: ").append(title).append("\n");
                    }
                    if (skinType != null) {
                        sb.append("Skin type: ").append(skinType).append("\n");
                    }
                    if (concerns != null) {
                        sb.append("Concerns: ").append(concerns).append("\n");
                    }
                    if (ageRange != null) {
                        sb.append("Age range: ").append(ageRange).append("\n");
                    }
                    sb.append("Text: ").append(text).append("\n");
                    sb.append("Score: ").append(hit.getScore()).append("\n");

                    return sb.toString();
                })
                .collect(Collectors.joining("\n---------------------\n"));
    }
}
