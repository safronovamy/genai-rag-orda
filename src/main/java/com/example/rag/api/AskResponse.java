package com.example.rag.api;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for /api/ask endpoint.
 * Includes final answer and debug info with retrieved documents.
 */
public class AskResponse {

    private String answer;
    private List<Map<String, Object>> contextDocuments;

    public AskResponse() {
    }

    public AskResponse(String answer, List<Map<String, Object>> contextDocuments) {
        this.answer = answer;
        this.contextDocuments = contextDocuments;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<Map<String, Object>> getContextDocuments() {
        return contextDocuments;
    }

    public void setContextDocuments(List<Map<String, Object>> contextDocuments) {
        this.contextDocuments = contextDocuments;
    }
}
