package com.example.rag.scrape.model;

/**
 * DTO representing a single product page from 82box.ru
 */
public record Box82Product(
        String url,
        String title,
        String about,
        String ingredients,
        String howToUse,
        String composition
) {}
