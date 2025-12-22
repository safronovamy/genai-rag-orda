package com.example.rag.scrape;

import java.util.List;

public final class Box82Selectors {
    private Box82Selectors() {}

    public static final String ABOUT = "О продукте";
    public static final String INGREDIENTS = "Ингредиенты";
    public static final String HOW_TO_USE = "Способ применения";

    public static final List<String> ABOUT_END = List.of(INGREDIENTS, HOW_TO_USE, "Состав", "Отзывы");
    public static final List<String> INGREDIENTS_END = List.of(HOW_TO_USE, "Состав", "Отзывы");
    public static final List<String> HOW_TO_USE_END = List.of("Состав", "Отзывы");
}
