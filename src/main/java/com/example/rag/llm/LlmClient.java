package com.example.rag.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Simple OpenAI Chat Completions client for RAG answers.
 */
public class LlmClient {

    private static final String CHAT_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4.1-mini";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LlmClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Ask the LLM a question with given context (already concatenated into a prompt).
     */
    public String askWithContext(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", MODEL);
        root.put("temperature", 0.0); // Make HyDE deterministic for stable retrieval evaluation

        ArrayNode messages = objectMapper.createArrayNode();

        // System message – role of the assistant
        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        // User message – question with context
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);

        root.set("messages", messages);

        String body = objectMapper.writeValueAsString(root);
        System.out.println(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CHAT_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("OpenAI chat API error: " + response.statusCode()
                    + " body: " + response.body());
        }

        JsonNode rootNode = objectMapper.readTree(response.body());
        JsonNode contentNode = rootNode
                .path("choices")
                .get(0)
                .path("message")
                .path("content");

        return contentNode.asText();
    }

    /**
     * Generate a retrieval-oriented "HyDE" note for semantic search.
     * This text is used ONLY for embeddings and vector retrieval, not for the final answer.
     */
    public String generateHydeText(String userQuery) throws Exception {
        String systemPrompt = """
            You are a search query generator for a skincare product knowledge base.
            Write a short retrieval-oriented search note using skincare terminology.
            Do NOT recommend products. Do NOT invent facts. Do NOT mention datasets or boxes.
            """;

        String userPrompt = """
            Convert the user request into a retrieval-oriented search note in English.
            Requirements:
            - 80–140 words
            - Include: skin type (if mentioned), main concerns, sensitivities, age (if mentioned),
              constraints (e.g., irritation, "no strong acids"), routine timing (AM/PM) if implied.
            - Use skincare terminology (e.g., hyperpigmentation, barrier repair, comedogenic, exfoliation, retinoid).
            - If info is missing, use generic placeholders like "unspecified skin type".
            Output ONLY the search note text.

            User request: %s
            """.formatted(userQuery);

        return askWithContext(systemPrompt, userPrompt);
    }


    /**
     * Generate a step-back retrieval note (principle-level abstraction) for fallback retrieval.
     * This text is used ONLY for embeddings and retrieval, not for the final answer.
     */
    public String generateStepBackText(String userQuery) throws Exception {
        String systemPrompt = """
            You are a retrieval query generator for a skincare RAG system.
            Your task is to abstract the user's question into the underlying skincare principle(s),
            such as active compatibility, irritation risk, frequency, routine ordering, cleansing rules, and SPF rules.
            Keep the output concise (2-3 sentences). Do not mention datasets or internal systems.
            Do not recommend products unless a product is explicitly mentioned by the user.
            """;

        String userPrompt = """
            Rewrite the user's request into a short principle-focused retrieval note in English.
            The goal is to retrieve general rules and ingredient guidance from the knowledge base.
            Return ONLY the retrieval note.

            User request:
            %s
            """.formatted(userQuery);

        return askWithContext(systemPrompt, userPrompt);
    }




}
