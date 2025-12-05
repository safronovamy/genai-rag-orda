package com.example.rag.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

public class EmbeddingClient {

    private static final String OPENAI_EMBEDDINGS_URL = "https://api.openai.com/v1/embeddings";
    private static final String MODEL = "text-embedding-3-small"; // 1536-dim embeddings

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EmbeddingClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Create embedding vector for given text using OpenAI embeddings API.
     */
    public List<Double> embed(String text) throws Exception {
        // Build request JSON: { "model": "...", "input": "..." }
        JsonNode requestBody = objectMapper.createObjectNode()
                .put("model", MODEL)
                .put("input", text);

        String bodyString = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_EMBEDDINGS_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyString))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("OpenAI embeddings API error: " + response.statusCode() +
                    " body: " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode embeddingArray = root.path("data").get(0).path("embedding");

        List<Double> vector = new ArrayList<>();
        for (JsonNode v : embeddingArray) {
            vector.add(v.asDouble());
        }

        return vector;
    }
}
