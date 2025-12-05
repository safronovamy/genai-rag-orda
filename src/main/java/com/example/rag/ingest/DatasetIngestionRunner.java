package com.example.rag.ingest;

import com.example.rag.model.SkincareDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DatasetIngestionRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasetIngestionRunner.class);

    private static final String COLLECTION_NAME = "skincare_box";

    public static void main(String[] args) throws Exception {
        // 1. Read OpenAI API key
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY env variable is not set");
        }

        // 2. Init helpers
        EmbeddingClient embeddingClient = new EmbeddingClient(apiKey);
        QdrantService qdrantService = new QdrantService();
        ObjectMapper objectMapper = new ObjectMapper();

        // 3. Load dataset from resources
        List<SkincareDocument> docs = loadDataset(objectMapper);
        log.info("Loaded {} documents from skincare_dataset.json", docs.size());

        // 4. Ensure collection exists in Qdrant
        qdrantService.ensureCollection(COLLECTION_NAME);
        log.info("Collection {} is ready", COLLECTION_NAME);

// 5. Generate embeddings
        List<List<Double>> embeddings = new ArrayList<>();
        for (SkincareDocument doc : docs) {
            String embeddingInput = buildEmbeddingText(doc);
            log.info("Embedding doc id={} type={} ...", doc.getId(), doc.getType());

            List<Double> vector = embeddingClient.embed(embeddingInput);
            embeddings.add(vector);
        }

// 6. Build Qdrant points with numeric IDs
        List<QdrantPoint> points = new ArrayList<>();

        for (int i = 0; i < docs.size(); i++) {
            SkincareDocument doc = docs.get(i);
            List<Double> vector = embeddings.get(i);

            // Build payload: here we keep the original string id
            Map<String, Object> payload = new HashMap<>();
            payload.put("doc_id", doc.getId());        // original string id
            payload.put("type", doc.getType());
            payload.put("title", doc.getTitle());
            payload.put("name", doc.getName());
            payload.put("brand", doc.getBrand());
            payload.put("category", doc.getCategory());
            payload.put("text", doc.getText());
            payload.put("skin_type", doc.getSkinType());
            payload.put("concerns", doc.getConcerns());
            payload.put("age_range", doc.getAgeRange());

            long numericId = i + 1L; // Qdrant point id: 1, 2, 3...
            points.add(new QdrantPoint(numericId, vector, payload));
        }

// 7. Upsert to Qdrant
        qdrantService.upsertBatch(COLLECTION_NAME, points);
        log.info("Ingestion completed successfully.");
    }

    /**
     * Load dataset from classpath resource skincare_dataset.json
     */
    private static List<SkincareDocument> loadDataset(ObjectMapper objectMapper) throws Exception {
        try (InputStream is = DatasetIngestionRunner.class
                .getClassLoader()
                .getResourceAsStream("skincare_dataset.json")) {

            if (is == null) {
                throw new IllegalStateException("Cannot find skincare_dataset.json on classpath");
            }

            return objectMapper.readValue(is, new TypeReference<List<SkincareDocument>>() {});
        }
    }

    /**
     * Build a text chunk that will be sent to embeddings model.
     * We combine the most important fields into one string.
     */
    private static String buildEmbeddingText(SkincareDocument doc) {
        StringBuilder sb = new StringBuilder();

        if (doc.getTitle() != null) {
            sb.append(doc.getTitle()).append("\n");
        }
        if (doc.getName() != null && (doc.getTitle() == null || !doc.getName().equals(doc.getTitle()))) {
            sb.append(doc.getName()).append("\n");
        }
        if (doc.getBrand() != null) {
            sb.append("Brand: ").append(doc.getBrand()).append("\n");
        }
        if (doc.getType() != null) {
            sb.append("Type: ").append(doc.getType()).append("\n");
        }
        if (doc.getCategory() != null) {
            sb.append("Category: ").append(doc.getCategory()).append("\n");
        }
        if (doc.getSkinType() != null && !doc.getSkinType().isEmpty()) {
            sb.append("Skin type: ").append(String.join(", ", doc.getSkinType())).append("\n");
        }
        if (doc.getConcerns() != null && !doc.getConcerns().isEmpty()) {
            sb.append("Concerns: ").append(String.join(", ", doc.getConcerns())).append("\n");
        }
        if (doc.getAgeRange() != null) {
            sb.append("Age range: ").append(doc.getAgeRange()).append("\n");
        }
        if (doc.getText() != null) {
            sb.append("\n").append(doc.getText());
        }

        return sb.toString();
    }
}
