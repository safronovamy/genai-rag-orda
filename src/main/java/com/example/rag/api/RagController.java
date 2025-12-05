package com.example.rag.api;

import com.example.rag.ingest.EmbeddingClient;
import com.example.rag.ingest.QdrantService;
import com.example.rag.ingest.SearchResult;
import com.example.rag.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller that exposes a simple RAG endpoint.
 *
 * POST /api/ask
 * {
 *   "question": "I am 35 with dry sensitive skin, what evening routine can I build from this box?"
 * }
 */
@RestController
@RequestMapping("/api")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private static final String COLLECTION_NAME = "skincare_box";

    private final EmbeddingClient embeddingClient;
    private final QdrantService qdrantService;
    private final LlmClient llmClient;

    public RagController(EmbeddingClient embeddingClient,
                         QdrantService qdrantService,
                         LlmClient llmClient) {
        this.embeddingClient = embeddingClient;
        this.qdrantService = qdrantService;
        this.llmClient = llmClient;
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) throws Exception {
        String question = request.getQuestion();
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be empty");
        }

        log.info("Received question: {}", question);

        // 1. Embed user question
        List<Double> questionVector = embeddingClient.embed(question);

        // 2. Search in Qdrant
        List<SearchResult> hits = qdrantService.search(COLLECTION_NAME, questionVector, 5);

        if (hits.isEmpty()) {
            return new AskResponse(
                    "Sorry, I could not find any relevant documents in the knowledge base.",
                    List.of()
            );
        }

        // 3. Build context string for LLM
        String context = buildContextFromHits(hits);

        // 4. Compose prompts for LLM
        String systemPrompt = """
                You are a skincare assistant specializing in Korean multi-step routines.
                Answer in English in a clear and practical way.
                Use ONLY the provided context about products, ingredients, routines and rules.
                If the information is missing, say that it is not present in the dataset.
                """;

        String userPrompt = """
                User question:
                %s

                Relevant context from knowledge base:
                %s
                """.formatted(question, context);

        // 5. Call LLM
        String answer = llmClient.askWithContext(systemPrompt, userPrompt);

        // 6. Prepare context docs for response (for debug / UI)
        List<Map<String, Object>> contextDocs = hits.stream()
                .map(SearchResult::getPayload)
                .collect(Collectors.toList());

        return new AskResponse(answer, contextDocs);
    }

    /**
     * Build a single text block from Qdrant search hits.
     */
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
