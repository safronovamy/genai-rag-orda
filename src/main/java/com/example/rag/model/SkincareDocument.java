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

    // Main free text (description, explanation, etc.)
    private String text;

    // For products / ingredients
    private List<String> concerns;
    private List<String> skinType;

    // For product actives (if you want them structured)
    private List<Active> actives;

    // For routines
    private List<String> steps;
    private String ageRange;

    // Optional extra metadata
    private Map<String, Object> extra;

    // ===== Getters & setters =====

    // Jackson uses getters/setters, you can generate them via IDE or Lombok.
    // Below only a few for brevity – в реальном коде сгенерируй все.

    public String getId() {
        return id;
    }

    @JsonProperty("id")
    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    @JsonProperty("type")
    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    @JsonProperty("title")
    public void setTitle(String title) {
        this.title = title;
    }

    public String getName() {
        return name;
    }

    @JsonProperty("name")
    public void setName(String name) {
        this.name = name;
    }

    public String getBrand() {
        return brand;
    }

    @JsonProperty("brand")
    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getCategory() {
        return category;
    }

    @JsonProperty("category")
    public void setCategory(String category) {
        this.category = category;
    }

    public String getText() {
        return text;
    }

    @JsonProperty("text")
    public void setText(String text) {
        this.text = text;
    }

    public List<String> getConcerns() {
        return concerns;
    }

    @JsonProperty("concerns")
    public void setConcerns(List<String> concerns) {
        this.concerns = concerns;
    }

    public List<String> getSkinType() {
        return skinType;
    }

    @JsonProperty("skin_type")
    public void setSkinType(List<String> skinType) {
        this.skinType = skinType;
    }

    public List<Active> getActives() {
        return actives;
    }

    @JsonProperty("actives")
    public void setActives(List<Active> actives) {
        this.actives = actives;
    }

    public List<String> getSteps() {
        return steps;
    }

    @JsonProperty("steps")
    public void setSteps(List<String> steps) {
        this.steps = steps;
    }

    public String getAgeRange() {
        return ageRange;
    }

    @JsonProperty("age_range")
    public void setAgeRange(String ageRange) {
        this.ageRange = ageRange;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    @JsonProperty("extra")
    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
    }

    // Nested class for actives
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Active {
        private String name;
        private String role;
        private String concentration;
        private List<String> targets;
        private List<String> functions;

        public String getName() {
            return name;
        }

        @JsonProperty("name")
        public void setName(String name) {
            this.name = name;
        }

        public String getRole() {
            return role;
        }

        @JsonProperty("role")
        public void setRole(String role) {
            this.role = role;
        }

        public String getConcentration() {
            return concentration;
        }

        @JsonProperty("concentration")
        public void setConcentration(String concentration) {
            this.concentration = concentration;
        }

        public List<String> getTargets() {
            return targets;
        }

        @JsonProperty("targets")
        public void setTargets(List<String> targets) {
            this.targets = targets;
        }

        public List<String> getFunctions() {
            return functions;
        }

        @JsonProperty("functions")
        public void setFunctions(List<String> functions) {
            this.functions = functions;
        }
    }

}