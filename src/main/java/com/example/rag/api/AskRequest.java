package com.example.rag.api;

/**
 * Request DTO for /api/ask endpoint.
 */
public class AskRequest {

    private String question;
    private String mode; // optional: "baseline" (default) or "hyde"

    public AskRequest() {}

    public AskRequest(String question) {
        this.question = question;
    }

    public AskRequest(String question, String mode) {
        this.question = question;
        this.mode = mode;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}
