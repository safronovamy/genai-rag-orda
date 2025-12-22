package com.example.rag.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SkincareDocument {

    // Common fields
    private String id;
    private String type; // product / routine / rule / ingredient
    private String title;
    private String name;
    private String brand;
    private String category;

    // Dataset fields frequently used for products
    private Integer step;

    private List<String> usageTime;   // JSON: usage_time
    private Boolean removesSpf;       // JSON: removes_spf
    private String source;

    // Main free text (description, explanation, etc.)
    private String text;

    // Existing dataset field (JSON: how_to_use)
    private String howToUse;

    // === Variant B: new extended product information ===
    private String about;        // JSON: about
    private String ingredients;  // JSON: ingredients
    private String composition;  // JSON: composition

    // For products / ingredients
    private List<String> concerns;
    private List<String> skinType;  // JSON: skin_type

    // For product actives (if you want them structured)
    private List<Active> actives;

    // For routines
    private List<String> steps;
    private String ageRange; // JSON: age_range

    // Optional extra metadata
    private Map<String, Object> extra;

    // ===== Getters & setters =====

    public String getId() { return id; }

    @JsonProperty("id")
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }

    @JsonProperty("type")
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }

    @JsonProperty("title")
    public void setTitle(String title) { this.title = title; }

    public String getName() { return name; }

    @JsonProperty("name")
    public void setName(String name) { this.name = name; }

    public String getBrand() { return brand; }

    @JsonProperty("brand")
    public void setBrand(String brand) { this.brand = brand; }

    public String getCategory() { return category; }

    @JsonProperty("category")
    public void setCategory(String category) { this.category = category; }

    public Integer getStep() { return step; }

    @JsonProperty("step")
    public void setStep(Integer step) { this.step = step; }

    public List<String> getUsageTime() { return usageTime; }

    @JsonProperty("usage_time")
    public void setUsageTime(List<String> usageTime) { this.usageTime = usageTime; }

    public Boolean getRemovesSpf() { return removesSpf; }

    @JsonProperty("removes_spf")
    public void setRemovesSpf(Boolean removesSpf) { this.removesSpf = removesSpf; }

    public String getSource() { return source; }

    @JsonProperty("source")
    public void setSource(String source) { this.source = source; }

    public String getText() { return text; }

    @JsonProperty("text")
    public void setText(String text) { this.text = text; }

    public String getHowToUse() { return howToUse; }

    @JsonProperty("how_to_use")
    public void setHowToUse(String howToUse) { this.howToUse = howToUse; }

    // ===== New fields (Variant B) =====

    public String getAbout() { return about; }

    @JsonProperty("about")
    public void setAbout(String about) { this.about = about; }

    public String getIngredients() { return ingredients; }

    @JsonProperty("ingredients")
    public void setIngredients(String ingredients) { this.ingredients = ingredients; }

    public String getComposition() { return composition; }

    @JsonProperty("composition")
    public void setComposition(String composition) { this.composition = composition; }

    // ===== Existing structured fields =====

    public List<String> getConcerns() { return concerns; }

    @JsonProperty("concerns")
    public void setConcerns(List<String> concerns) { this.concerns = concerns; }

    public List<String> getSkinType() { return skinType; }

    @JsonProperty("skin_type")
    public void setSkinType(List<String> skinType) { this.skinType = skinType; }

    public List<Active> getActives() { return actives; }

    @JsonProperty("actives")
    public void setActives(List<Active> actives) { this.actives = actives; }

    public List<String> getSteps() { return steps; }

    @JsonProperty("steps")
    public void setSteps(List<String> steps) { this.steps = steps; }

    public String getAgeRange() { return ageRange; }

    @JsonProperty("age_range")
    public void setAgeRange(String ageRange) { this.ageRange = ageRange; }

    public Map<String, Object> getExtra() { return extra; }

    @JsonProperty("extra")
    public void setExtra(Map<String, Object> extra) { this.extra = extra; }

    // Nested class for actives
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Active {
        private String name;
        private String role;
        private String concentration;
        private List<String> targets;
        private List<String> functions;

        public String getName() { return name; }

        @JsonProperty("name")
        public void setName(String name) { this.name = name; }

        public String getRole() { return role; }

        @JsonProperty("role")
        public void setRole(String role) { this.role = role; }

        public String getConcentration() { return concentration; }

        @JsonProperty("concentration")
        public void setConcentration(String concentration) { this.concentration = concentration; }

        public List<String> getTargets() { return targets; }

        @JsonProperty("targets")
        public void setTargets(List<String> targets) { this.targets = targets; }

        public List<String> getFunctions() { return functions; }

        @JsonProperty("functions")
        public void setFunctions(List<String> functions) { this.functions = functions; }
    }
}
