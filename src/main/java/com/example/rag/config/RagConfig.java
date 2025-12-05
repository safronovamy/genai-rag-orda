package com.example.rag.config;

import com.example.rag.ingest.EmbeddingClient;
import com.example.rag.ingest.QdrantService;
import com.example.rag.llm.LlmClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that wires core RAG components as beans.
 */
@Configuration
public class RagConfig {

    @Bean
    public EmbeddingClient embeddingClient() {
        // We still read API key from env.
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY env variable is not set");
        }
        return new EmbeddingClient(apiKey);
    }

    @Bean
    public QdrantService qdrantService() {
        // Default constructor uses localhost:6333
        return new QdrantService();
    }

    @Bean
    public LlmClient llmClient() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY env variable is not set");
        }
        return new LlmClient(apiKey);
    }
}
