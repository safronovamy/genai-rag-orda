package com.example.rag.ingest;

import java.util.Map;

/**
 * Holds a single Qdrant search result with score and payload.
 */
public class SearchResult {
    private final double score;
    private final Map<String, Object> payload;

    public SearchResult(double score, Map<String, Object> payload) {
        this.score = score;
        this.payload = payload;
    }

    public double getScore() {
        return score;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return "SearchResult{" +
                "score=" + score +
                ", payload=" + payload +
                '}';
    }


}
