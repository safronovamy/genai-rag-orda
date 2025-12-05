package com.example.rag.ingest;

import java.util.List;
import java.util.Map;

/**
 * Simple data holder for a Qdrant point.
 * Encapsulates numeric id, embedding vector and payload (metadata).
 */
public class QdrantPoint {

    private final long id; // Qdrant requires unsigned integer or UUID
    private final List<Double> vector;
    private final Map<String, Object> payload;

    public QdrantPoint(long id, List<Double> vector, Map<String, Object> payload) {
        this.id = id;
        this.vector = vector;
        this.payload = payload;
    }

    public long getId() {
        return id;
    }

    public List<Double> getVector() {
        return vector;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
