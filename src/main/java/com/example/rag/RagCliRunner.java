package com.example.rag;

import com.example.rag.ingest.EmbeddingClient;
import com.example.rag.ingest.QdrantService;
import com.example.rag.ingest.SearchResult;
import com.example.rag.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * Simple CLI runner:
 * - reads a question from stdin
 * - generates embedding
 * - searches Qdrant
 * - calls LLM with context
 * - prints the answer
 */
public class RagCliRunner {

    private static final Logger log = LoggerFactory.getLogger(RagCliRunner.class);

    private static final String COLLECTION_NAME = "skincare_box";

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY env variable is not set");
        }

        EmbeddingClient embeddingClient = new EmbeddingClient(apiKey);
        QdrantService qdrantService = new QdrantService();
        LlmClient llmClient = new LlmClient(apiKey);

        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter your skincare question (or 'exit' to quit):");

        while (true) {
            System.out.print("> ");
            String question = scanner.nextLine();
            if (question == null || question.trim().equalsIgnoreCase("exit")) {
                System.out.println("Bye!");
                break;
            }

            // 1. Embed the question
            log.info("Creating embedding for the question...");
            List<Double> questionVector = embeddingClient.embed(question);

            // 2. Search in Qdrant
            log.info("Searching in Qdrant...");
            List<SearchResult> hits = qdrantService.search(COLLECTION_NAME, questionVector, 5);

            if (hits.isEmpty()) {
                System.out.println("No relevant documents found in the knowledge base.");
                continue;
            }

            // 3. Build context string from payloads
            String context = buildContextFromHits(hits);

            // 4. Compose prompts
            String systemPrompt = """
                    You are a skincare assistant specializing in Korean multi-step routines.
                    Answer in English, in a clear and practical way.
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
            log.info("Calling LLM with context...");
            String answer = llmClient.askWithContext(systemPrompt, userPrompt);

            System.out.println("\n--- RAG answer ---");
            System.out.println(answer);
            System.out.println("------------------\n");
        }
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
