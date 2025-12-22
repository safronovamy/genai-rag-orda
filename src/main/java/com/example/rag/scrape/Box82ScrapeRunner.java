package com.example.rag.scrape;

import com.example.rag.scrape.model.Box82Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

public class Box82ScrapeRunner {

    public static void main(String[] args) throws Exception {

        // resource path внутри classpath
        String urlsResource = "82box/urls_82box.txt";

        // output
        Path outJson = Paths.get(args.length > 0 ? args[0] : "82box_products.json");

        // headless: true по умолчанию
        boolean headless = args.length <= 1 || Boolean.parseBoolean(args[1]);

        // ⬇⬇⬇ ВОТ ЗДЕСЬ ГЛАВНОЕ ИЗМЕНЕНИЕ
        List<String> urls = Box82Scraper.readUrlsFromResource(urlsResource);

        System.out.println("URLs loaded: " + urls.size());

        Box82Scraper scraper = new Box82Scraper();
        List<Box82Product> results = scraper.scrapeAll(urls);

        writeJson(results, outJson);
        System.out.println("Saved: " + outJson.toAbsolutePath());
    }

    private static void writeJson(List<Box82Product> items, Path out) throws Exception {
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(
                out,
                om.writeValueAsString(items),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }
}
