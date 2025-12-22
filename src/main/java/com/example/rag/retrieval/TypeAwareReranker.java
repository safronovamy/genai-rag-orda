package com.example.rag.retrieval;

import com.example.rag.ingest.SearchResult;

import java.util.*;

public final class TypeAwareReranker {

    public static List<SearchResult> rerank(
            List<SearchResult> candidates,
            int topK,
            double relativeDelta,
            int maxSameTypeInTopK
    ) {
        // Soft diversity reranking: preserve semantic ranking, apply type diversity only when scores are close.
        if (candidates == null || candidates.isEmpty() || topK <= 0) return List.of();

        List<SearchResult> pool = new ArrayList<>(candidates);

        // Deterministic ordering: score desc, then doc_id asc
        pool.sort((a, b) -> {
            int cmp = Double.compare(b.getScore(), a.getScore());
            if (cmp != 0) return cmp;
            return safeDocId(a).compareTo(safeDocId(b));
        });

        List<SearchResult> out = new ArrayList<>(topK);
        Set<String> usedDocIds = new HashSet<>();
        Map<String, Integer> typeCounts = new HashMap<>();

        while (out.size() < topK) {
            SearchResult best = firstUnused(pool, usedDocIds);
            if (best == null) break;

            String bestType = safeType(best);
            double bestScore = best.getScore();

            boolean overRep = typeCounts.getOrDefault(bestType, 0) >= maxSameTypeInTopK;

            SearchResult chosen = best;
            if (overRep) {
                SearchResult alt = findAlternativeDifferentTypeWithinDelta(pool, usedDocIds, bestType, bestScore, relativeDelta);
                if (alt != null) {
                    chosen = alt;
                }
            }

            String docId = safeDocId(chosen);
            if (docId.isBlank()) {
                // Skip malformed payloads deterministically.
                usedDocIds.add(UUID.randomUUID().toString());
                continue;
            }

            if (usedDocIds.add(docId)) {
                out.add(chosen);
                String t = safeType(chosen);
                typeCounts.put(t, typeCounts.getOrDefault(t, 0) + 1);
            }
        }

        return out;
    }

    private static SearchResult firstUnused(List<SearchResult> pool, Set<String> usedDocIds) {
        for (SearchResult r : pool) {
            String docId = safeDocId(r);
            if (!docId.isBlank() && !usedDocIds.contains(docId)) return r;
        }
        return null;
    }

    private static SearchResult findAlternativeDifferentTypeWithinDelta(
            List<SearchResult> pool,
            Set<String> usedDocIds,
            String excludedType,
            double bestScore,
            double relativeDelta
    ) {
        double minScore = bestScore * (1.0 - relativeDelta);

        for (SearchResult r : pool) {
            String docId = safeDocId(r);
            if (docId.isBlank() || usedDocIds.contains(docId)) continue;

            String t = safeType(r);
            if (t.equalsIgnoreCase(excludedType)) continue;

            if (r.getScore() >= minScore) {
                return r; // pool is sorted desc -> first match is best alt
            } else {
                break; // below threshold -> stop scanning
            }
        }
        return null;
    }

    private static String safeDocId(SearchResult r) {
        if (r == null || r.getPayload() == null) return "";
        Object v = r.getPayload().get("doc_id");
        return v == null ? "" : v.toString().trim();
    }

    private static String safeType(SearchResult r) {
        if (r == null || r.getPayload() == null) return "unknown";
        Object v = r.getPayload().get("type");
        return v == null ? "unknown" : v.toString().trim().toLowerCase(Locale.ROOT);
    }

    private TypeAwareReranker() {}
}
