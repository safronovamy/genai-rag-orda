package com.example.rag.api;

/**
 * Request DTO for /api/ask endpoint.
 */
public class AskRequest {

    private String question;

    public AskRequest() {
    }

    public AskRequest(String question) {
        this.question = question;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
