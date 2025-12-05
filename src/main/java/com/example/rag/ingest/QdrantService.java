package com.example.rag.ingest;

//import com.example.rag.model.SkincareDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class QdrantService {

    private static final String DEFAULT_HOST = "http://localhost:6333";
    private static final int VECTOR_SIZE = 1536; // must match the embedding model size

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public QdrantService() {
        this(DEFAULT_HOST);
    }

    public QdrantService(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Create collection if it does not exist.
     */
    public void ensureCollection(String collectionName) throws Exception {
        // Qdrant create collection: PUT /collections/{name}
        // body: { "vectors": { "size": 1536, "distance": "Cosine" } }

        ObjectNode vectorsNode = objectMapper.createObjectNode();
        vectorsNode.put("size", VECTOR_SIZE);
        vectorsNode.put("distance", "Cosine");

        ObjectNode root = objectMapper.createObjectNode();
        root.set("vectors", vectorsNode);

        String body = objectMapper.writeValueAsString(root);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/collections/" + collectionName))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Qdrant returns 200 even if collection already exists; we just log errors if any.
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Failed to create collection: " + response.statusCode()
                    + " body: " + response.body());
        }
    }

    /**
     * Upsert a batch of documents with their embeddings into Qdrant.
     */
    public void upsertBatch(String collectionName, List<QdrantPoint> points) throws Exception {
        // Build Qdrant "points" array: [{ id, vector, payload }, ...]
        ArrayNode pointsArray = objectMapper.createArrayNode();

        for (QdrantPoint p : points) {
            ObjectNode pointNode = objectMapper.createObjectNode();

            // Numeric id – Qdrant requires unsigned integer or UUID for older versions
            pointNode.put("id", p.getId());

            // Vector – array of floats/doubles
            ArrayNode vectorNode = objectMapper.createArrayNode();
            for (Double v : p.getVector()) {
                vectorNode.add(v);
            }
            pointNode.set("vector", vectorNode);

            // Payload – arbitrary JSON, mapped from our Map<String, Object>
            JsonNode payloadNode = objectMapper.valueToTree(p.getPayload());
            pointNode.set("payload", payloadNode);

            pointsArray.add(pointNode);
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.set("points", pointsArray);

        String body = objectMapper.writeValueAsString(root);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/collections/" + collectionName + "/points?wait=true"))
                .header("Content-Type", "application/json")
                // Qdrant upsert points as PUT with "points" list
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("Failed to upsert points: " + response.statusCode()
                    + " body: " + response.body());
        }
    }

    /**
     * Search the collection by vector and return topK hits with payload.
     */
    public List<SearchResult> search(String collectionName, List<Double> vector, int topK) throws Exception {
        // Build request JSON:
        // {
        //   "vector": [...],
        //   "top": topK,
        //   "with_payload": true
        // }
        ObjectNode root = objectMapper.createObjectNode();

        ArrayNode vectorNode = objectMapper.createArrayNode();
        for (Double v : vector) {
            vectorNode.add(v);
        }
        root.set("vector", vectorNode);
        root.put("top", topK);
        root.put("with_payload", true);
        root.put("with_vectors", false);

        String body = objectMapper.writeValueAsString(root);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/collections/" + collectionName + "/points/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("Failed to search points: " + response.statusCode()
                    + " body: " + response.body());
        }

        JsonNode rootNode = objectMapper.readTree(response.body());
        JsonNode resultArray = rootNode.path("result");
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode pointNode : resultArray) {
            double score = pointNode.path("score").asDouble();
            JsonNode payloadNode = pointNode.path("payload");

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.convertValue(
                    payloadNode,
                    Map.class
            );

            results.add(new SearchResult(score, payload));
        }

        return results;
    }

}
