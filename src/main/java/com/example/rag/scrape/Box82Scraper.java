package com.example.rag.scrape;

import com.example.rag.scrape.model.Box82Product;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Scraper for 82box.ru product pages.
 *
 * IMPORTANT:
 * Product tab content exists ONLY in DOM after tab click.
 * There is no API / JSON source for these fields.
 *
 * Strategy:
 * 1) Click tab by visible text
 * 2) Wait until DOM content after tabs changes
 * 3) Read first text block after tabs wrapper
 */
public class Box82Scraper {

    // Tab titles as they appear on the site
    private static final String TAB_ABOUT = "О продукте";
    private static final String TAB_INGREDIENTS = "Ингредиенты";
    private static final String TAB_HOW_TO_USE = "Способ применения";
    private static final String TAB_COMPOSITION = "Состав";

    /**
     * Scrapes all provided product URLs.
     */
    public List<Box82Product> scrapeAll(List<String> urls) {

        List<Box82Product> results = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(false) // keep visible for stability/debug
            );

            BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions()
                            .setLocale("ru-RU")
                            .setViewportSize(1400, 900)
            );

            Page page = context.newPage();
            page.setDefaultTimeout(60_000);

            for (int i = 0; i < urls.size(); i++) {
                String url = urls.get(i);

                try {
                    Box82Product product = parseSingleProduct(page, url);
                    results.add(product);
                    System.out.printf("[%d/%d] OK%n", i + 1, urls.size());
                } catch (Exception e) {
                    System.out.printf(
                            "[%d/%d] FAIL: %s -> %s%n",
                            i + 1, urls.size(), url, e.getMessage()
                    );
                    results.add(new Box82Product(url, null, null, null, null, null));
                }

                page.waitForTimeout(300);
            }

            context.close();
            browser.close();
        }

        return results;
    }

    /**
     * Parses a single product page.
     */
    private Box82Product parseSingleProduct(Page page, String url) {

        page.navigate(url);
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);

        // Product title
        Locator h1 = page.locator("h1").first();
        h1.waitFor();
        String title = normalize(h1.innerText());

        // Tabs content (optional)
        String about = null;
        String ingredients = null;
        String howToUse = null;
        String composition = null;

        if (tryClickTab(page, TAB_ABOUT)) {
            about = readActiveTabContent(page);
        }

        if (tryClickTab(page, TAB_INGREDIENTS)) {
            ingredients = readActiveTabContent(page);
        }

        if (tryClickTab(page, TAB_HOW_TO_USE)) {
            howToUse = readActiveTabContent(page);
        }

        if (tryClickTab(page, TAB_COMPOSITION)) {
            composition = readActiveTabContent(page);
        }

        return new Box82Product(
                url,
                title,
                emptyToNull(about),
                emptyToNull(ingredients),
                emptyToNull(howToUse),
                emptyToNull(composition)
        );
    }

    /**
     * Tries to click a tab by visible text.
     * Returns true if the tab was found and clicked, false otherwise.
     */
    private boolean tryClickTab(Page page, String tabTitle) {

        Locator tab = page.locator(
                "div.ProductInfoTabsBlock_tabsWrapper__9aBNg >> text=\"" + tabTitle + "\""
        );

        if (tab.count() == 0) {
            // Tab is not present for this product (some products have fewer tabs).
            return false;
        }

        tab.first().scrollIntoViewIfNeeded();
        tab.first().click();

        // Wait until content right after tabs wrapper has some meaningful text
        page.waitForFunction(
                "() => {" +
                        "const w = document.querySelector('div.ProductInfoTabsBlock_tabsWrapper__9aBNg');" +
                        "if (!w) return false;" +
                        "const n = w.nextElementSibling;" +
                        "return n && n.innerText && n.innerText.trim().length > 10;" +
                        "}"
        );

        return true;
    }

    /**
     * Reads content of the currently active product tab.
     *
     * Handles both layouts:
     * - div.flex-lines.gap16 (lists, paragraphs)
     * - p.text-b1 (composition / INCI)
     */
    private String readActiveTabContent(Page page) {

        Locator tabsWrapper = page.locator("div.ProductInfoTabsBlock_tabsWrapper__9aBNg").first();
        tabsWrapper.waitFor();

        Locator content = tabsWrapper.locator(
                "xpath=following-sibling::*[self::div or self::p][1]"
        ).first();

        if (content.count() == 0) return null;

        String text = normalize(content.innerText());

        if (text == null || text.isBlank()) return null;
        if (text.startsWith("Отзывы")) return null;

        return text;
    }

    // ---------- helpers ----------

    private String normalize(String text) {
        if (text == null) return null;
        return text.replace('\u00A0', ' ').trim();
    }

    private String emptyToNull(String text) {
        if (text == null) return null;
        String t = text.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Utility method: read URLs from a resource file (one URL per line).
     */
    public static List<String> readUrlsFromResource(String resourcePath) throws Exception {
        try (var is = Box82Scraper.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Resource not found: " + resourcePath);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {

                return reader.lines()
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .collect(Collectors.toList());
            }
        }
    }
}
