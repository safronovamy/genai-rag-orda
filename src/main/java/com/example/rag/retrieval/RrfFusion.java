package com.example.rag.retrieval;

import com.example.rag.ingest.SearchResult;

import java.util.*;

/**
 * Reciprocal Rank Fusion for merging multiple ranked lists.
 * Uses doc_id from payload as the stable key.
 */
public final class RrfFusion {

    public static List<SearchResult> fuse(List<SearchResult> a, List<SearchResult> b, int limit, int k) {
        Map<String, SearchResult> bestById = new HashMap<>();
        Map<String, Double> rrfScore = new HashMap<>();

        addList(a, k, bestById, rrfScore);
        addList(b, k, bestById, rrfScore);

        List<Map.Entry<String, Double>> scored = new ArrayList<>(rrfScore.entrySet());
        scored.sort((x, y) -> Double.compare(y.getValue(), x.getValue())); // desc

        List<SearchResult> out = new ArrayList<>(Math.min(limit, scored.size()));
        for (Map.Entry<String, Double> e : scored) {
            SearchResult sr = bestById.get(e.getKey());
            if (sr != null) out.add(sr);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private static void addList(
            List<SearchResult> list,
            int k,
            Map<String, SearchResult> bestById,
            Map<String, Double> rrfScore
    ) {
        if (list == null) return;

        for (int i = 0; i < list.size(); i++) {
            SearchResult r = list.get(i);
            String docId = safeDocId(r);
            if (docId.isBlank()) continue;

            // Keep any representative SearchResult for this doc_id (payload matters most).
            bestById.putIfAbsent(docId, r);

            int rank = i + 1; // 1-based
            double add = 1.0 / (k + rank);
            rrfScore.put(docId, rrfScore.getOrDefault(docId, 0.0) + add);
        }
    }

    private static String safeDocId(SearchResult r) {
        if (r == null || r.getPayload() == null) return "";
        Object v = r.getPayload().get("doc_id");
        return v == null ? "" : v.toString().trim();
    }

    private RrfFusion() {}
}
